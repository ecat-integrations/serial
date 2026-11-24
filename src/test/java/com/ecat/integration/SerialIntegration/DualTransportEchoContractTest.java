package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.TcpIntegration.config.TcpConfig;

/**
 * 双传输回声契约（P4 验收 6：同一份问答式契约在 COM 与 TCP 下逐字同绿）：
 * 同一 contract 函数驱动两条物理通道——COM=socat PTY 对（ECAT 开 A 端，对端读写 B 端）、
 * TCP=loopback ServerSocket（ECAT 连入，对端在已 accept 连接上读写）。每轮：传输层写
 * 命令 → 对端收满命令帧后回写应答 → sink 累收内容与期望应答流逐字一致（分块形态无关，
 * 按拼接比较）。断言全计数/阻塞读承载，无 sleep 猜测；socat 不可用整用例 Assume 跳过
 * （TCP 单侧契约已由 TcpTransportContractTest 独立钉住）。
 *
 * @author coffee
 */
public class DualTransportEchoContractTest {

    /** 问答帧形：8 字节命令 / 10 字节应答（首字节=轮次序号，兼做乱序探测器）。 */
    private static final int ROUNDS = 8;
    private static final int CMD_LEN = 8;
    private static final int RESP_LEN = 10;

    private Process socat;
    private File ptyA;
    private File ptyB;
    private File ptyDir;

    @Before
    public void setUp() {
        // 两条腿各自建资源；PTY 对在 COM 腿内创建
    }

    @After
    public void tearDown() {
        if (socat != null) {
            socat.destroy();
            socat = null;
        }
    }

    /**
     * 问答契约本体（两种传输共用，逐字同一函数）：rounds 轮「写命令→对端回应答」，
     * 返回 sink 累收的全部字节（调用方与期望应答流比对）。
     */
    private byte[] runEchoContract(PortTransport transport, EchoPeer peer) throws Exception {
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading((buf, len) -> delivered.add(len == buf.length ? buf : Arrays.copyOf(buf, len)));
        for (int round = 0; round < ROUNDS; round++) {
            byte[] command = new byte[CMD_LEN];
            Arrays.fill(command, (byte) 0xA0);
            command[1] = (byte) round;
            assertEquals("命令写出字节数", CMD_LEN, transport.write(command, command.length));
            // 对端收满命令帧后回写本轮应答（responder 线程承载阻塞读）
            peer.respondOnCommand(command);
        }
        int expectedLen = ROUNDS * RESP_LEN;
        byte[] joined = new byte[expectedLen];
        int off = 0;
        while (off < expectedLen) {
            byte[] chunk = delivered.poll(5, TimeUnit.SECONDS);
            if (chunk == null) {
                fail("5s 内应答未到齐（收到 " + off + "/" + expectedLen + " B）");
            }
            int copy = Math.min(chunk.length, joined.length - off);
            System.arraycopy(chunk, 0, joined, off, copy);
            off += copy;
        }
        return joined;
    }

    /** 期望应答流（契约推导，非输出反推）：每轮 RESP_LEN 字节，首字节=轮次序号。 */
    private static byte[] expectedResponses() {
        byte[] expected = new byte[ROUNDS * RESP_LEN];
        for (int round = 0; round < ROUNDS; round++) {
            expected[round * RESP_LEN] = (byte) round;
            Arrays.fill(expected, round * RESP_LEN + 1, (round + 1) * RESP_LEN, (byte) 0x5A);
        }
        return expected;
    }

    @Test(timeout = 60_000)
    public void echoContract_identicalOverComAndTcp() throws Exception {
        // ---------- COM 腿（socat PTY 对；不可用则本用例跳过） ----------
        assumeRealPty();
        SerialSourcePort comPort = new SerialSourcePort(
                new SerialInfo(ptyA.getAbsolutePath(), 9600, 8, 1, 0, 0, 500), 3, null);
        PortTransport comTransport = comPort.transport();
        assertTrue("COM 腿打开必须成功", comTransport.open());
        byte[] comReceived;
        try (PtyEchoPeer peer = new PtyEchoPeer()) {
            comReceived = runEchoContract(comTransport, peer);
        } finally {
            comTransport.close();
        }
        assertTrue("COM 腿应答逐字一致（含轮次序号序）",
                Arrays.equals(expectedResponses(), comReceived));

        // ---------- TCP 腿（loopback ServerSocket，同一契约同一断言） ----------
        try (ServerSocket server = new ServerSocket(0)) {
            TcpTransport tcpTransport = new TcpTransport("127.0.0.1", server.getLocalPort(), "dual-echo-tcp",
                    // 显式全参（无隐默认）：回声契约不断线，重连参数仅按构造契约必填面给值
                    TcpConfig.builder()
                            .host("127.0.0.1").port(server.getLocalPort())
                            .maxReconnectAttempts(3)
                            .reconnectTimeout(java.time.Duration.ofMillis(300))
                            .connectionTimeout(java.time.Duration.ofSeconds(2))
                            .build());
            assertTrue("TCP 腿打开必须成功", tcpTransport.open());
            Socket peerSocket = server.accept();
            byte[] tcpReceived;
            try (SocketEchoPeer peer = new SocketEchoPeer(peerSocket)) {
                tcpReceived = runEchoContract(tcpTransport, peer);
            } finally {
                tcpTransport.close();
            }
            assertTrue("TCP 腿应答逐字一致（与 COM 腿同一契约同一期望）",
                    Arrays.equals(expectedResponses(), tcpReceived));
        }
    }

    // ==================== 对端（收满命令→回写应答） ====================

    /** 问答对端统一面（responder 线程在 {@link #respondOnCommand} 注册的应答器上循环）。 */
    private interface EchoPeer extends AutoCloseable {
        /** 注册下一轮命令的应答（对端收满 command 即写回应答；阻塞读由对端线程承载）。 */
        void respondOnCommand(byte[] command) throws IOException;
    }

    /** PTY 对端：B 端收命令（阻塞 FileInputStream），同端写应答。 */
    private final class PtyEchoPeer implements EchoPeer {

        private final InputStream in;
        private final OutputStream out;

        PtyEchoPeer() throws IOException {
            this.in = new FileInputStream(ptyB);
            this.out = new FileOutputStream(ptyB);
        }

        @Override
        public void respondOnCommand(byte[] command) throws IOException {
            // 阻塞读满命令帧（raw PTY 无回显，读到的即 ECAT 写出的命令）；读满即回写
            byte[] seen = new byte[CMD_LEN];
            int off = 0;
            while (off < CMD_LEN) {
                int read = in.read(seen, off, CMD_LEN - off);
                if (read < 0) {
                    fail("PTY 对端命令流提前 EOF");
                }
                off += read;
            }
            assertEquals("对端收到的应与发出的命令逐字一致", true, Arrays.equals(command, seen));
            out.write(buildResponse(command));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            in.close();
            out.close();
        }
    }

    /** TCP 对端：已 accept 连接上收满命令→回写应答（同 PTY 对端同逻辑）。 */
    private static final class SocketEchoPeer implements EchoPeer {

        private final Socket socket;

        SocketEchoPeer(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void respondOnCommand(byte[] command) throws IOException {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] seen = new byte[CMD_LEN];
            int off = 0;
            while (off < CMD_LEN) {
                int read = in.read(seen, off, CMD_LEN - off);
                if (read < 0) {
                    fail("TCP 对端命令流提前 EOF");
                }
                off += read;
            }
            assertEquals("对端收到的应与发出的命令逐字一致", true, Arrays.equals(command, seen));
            out.write(buildResponse(command));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 应答帧（契约推导）：首字节=命令携带的轮次序号，余字节固定填充。 */
    private static byte[] buildResponse(byte[] command) {
        byte[] response = new byte[RESP_LEN];
        response[0] = command[1];
        Arrays.fill(response, 1, RESP_LEN, (byte) 0x5A);
        return response;
    }

    // ==================== PTY 对（复用既有 socat 模式） ====================

    /** 起一对真实 PTY（socat）；socat 不可用 Assume 跳过本用例。 */
    private void assumeRealPty() throws IOException {
        ptyDir = Files.createTempDirectory("dual-echo-pty").toFile();
        ptyDir.deleteOnExit();
        ptyA = new File(ptyDir, "ptyA");
        ptyB = new File(ptyDir, "ptyB");
        try {
            socat = new ProcessBuilder("socat", "-d", "-d",
                    "pty,raw,echo=0,link=" + ptyA.getAbsolutePath(),
                    "pty,raw,echo=0,link=" + ptyB.getAbsolutePath())
                    .redirectErrorStream(true).start();
        } catch (IOException socatUnavailable) {
            Assume.assumeNoException("socat 不可用，双传输契约用例跳过（TCP 单侧已由 TcpTransportContractTest 钉住）",
                    socatUnavailable);
        }
        awaitTrue("PTY link 就绪", () -> ptyA.exists() && ptyB.exists(), 5000);
    }

    /** 条件有界等待（5ms 步进轮询，超时 fail）。 */
    private static void awaitTrue(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("condition not met within " + timeoutMs + "ms: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while awaiting: " + what);
            }
        }
    }
}
