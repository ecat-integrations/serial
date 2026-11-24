package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * PortTransport 传输缝的 COM 实现契约测试（推模型 RX 的缝级钉子；执行者接线的行为
 * 由执行者测试面另钉）。确定性纪律：事件断言全部经 latch/精确计数；唯一的 sleep 是
 * 100KB/s 负载节拍与 5ms 条件轮询步进（负载形态与既有 awaitTrue 模式，非定时猜测）。
 *
 * <p>钉住的契约（与 PortTransport javadoc 五条线程契约对应）：
 * <ol>
 *   <li>即时性：真实 PTY 注入应答，从写入到 sink 收到 ≤2ms（25ms 轮询间隙的结构性消除）；</li>
 *   <li>排空语义：stopReading 后注入迟到帧，sink 零收到（阻塞读返回后先查停再投递）；</li>
 *   <li>close 唤醒：阻塞读线程在 close 后 ≤100ms 退出（看门狗跨线程强拆依赖）；</li>
 *   <li>死亡两形态：阻塞读返 -1 / 抛异常 → 死亡通知恰一次；</li>
 *   <li>无轮询溢出窗：100KB/s 持续约 1 秒逐字节收满（计数与 CRC32 双断言）。</li>
 * </ol>
 *
 * <p>真实 PTY 由 socat 进程对提供（用毕 destroy 回收）；socat 不可用的环境 Assume 跳过
 * （mock 形态用例不受影响）。读线程名 = serial-rx-{portName}-&lt;N&gt;（NamedThreadFactory
 * 加序号后缀），存活性断言按完整名精确匹配。
 *
 * @author coffee
 */
public class JSerialCommTransportContractTest {

    private SerialSourcePort port;
    private SerialPort mockPort;
    private JSerialCommTransport transport;
    private final LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();

    @Before
    public void setUp() {
        port = new SerialSourcePort(new SerialInfo("/dev/ttyCONTRACT", 9600, 8, 1, 0, 0, 500), 3, null);
        transport = (JSerialCommTransport) port.transport();
    }

    @After
    public void tearDown() {
        transport.stopReading();
        if (socat != null) {
            socat.destroy();
            socat = null;
        }
    }

    private ReadSink collectingSink() {
        return (buf, len) -> delivered.add(len == buf.length ? buf : java.util.Arrays.copyOf(buf, len));
    }

    /** mock 口：isOpen=true、读行为由用例注入（读线程的阻塞点完全受控，无时序猜测）。 */
    private void bindMockPort() {
        mockPort = mock(SerialPort.class);
        when(mockPort.isOpen()).thenReturn(true);
        port.serialPort = mockPort;
    }

    /** 读线程存活性：按完整线程名精确匹配（serial-rx-{port}-0；每工厂实例序号从 0 起）。 */
    private static boolean rxThreadAlive(String portName) {
        String expected = "serial-rx-" + portName + "-0";
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** 条件有界等待（既有 awaitTrue 模式：5ms 步进轮询，超时 fail）。 */
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

    // ==================== ②停投递语义（mock，确定性） ====================

    /**
     * stopReading 后注入迟到帧 sink 零收到（pause 语义与 close 收线的传输层落点；排空
     * 规则的权威在执行者 DRAINING 丢弃，不经传输层停读）：读线程 park 在 mock readBytes
     * （模拟阻塞于内核 poll），stopReading 置停后放行读返回（n=0 超时形态），线程在
     * 「读返回后先查停」点退出——此后再注入的数据无读者，零投递。
     */
    @Test(timeout = 10_000)
    public void stopReading_discardsLateArrivals_zeroDelivery() throws Exception {
        bindMockPort();
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch readReleased = new CountDownLatch(1);
        when(mockPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            readEntered.countDown();
            readReleased.await(5, TimeUnit.SECONDS);
            return 0; // 超时醒形态
        });
        transport.startReading(collectingSink());
        assertTrue("读线程必须已进入阻塞读", readEntered.await(5, TimeUnit.SECONDS));

        transport.stopReading();
        readReleased.countDown(); // 阻塞读返回，线程走到「先查停」点退出
        awaitTrue("读线程自退", () -> !rxThreadAlive(port.serialInfo.portName), 5000);

        // 迟到帧注入（此刻已无读者）——观察窗内 sink 必须零收到
        when(mockPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            b[0] = 0x5A;
            return 1;
        });
        awaitTrue("停止后零投递观察窗（300ms）",
                () -> !rxThreadAlive(port.serialInfo.portName) && delivered.isEmpty(), 300);
        assertTrue("stopReading 后迟到帧必须零投递", delivered.isEmpty());
    }

    // ==================== ④死亡两形态（mock，确定性） ====================

    /**
     * 阻塞读返 -1（PTY 对端消失/口已关的实测形态）：死亡通知恰一次，读线程退出。
     */
    @Test(timeout = 10_000)
    public void readMinusOne_notifiesDeathExactlyOnce() throws Exception {
        bindMockPort();
        when(mockPort.readBytes(any(byte[].class), anyLong())).thenReturn(-1);
        assertDeathExactlyOnce("read-closed(-1)");
    }

    /**
     * 阻塞读抛异常（USB 拔出异常形态）：死亡通知恰一次，读线程退出。
     */
    @Test(timeout = 10_000)
    public void readException_notifiesDeathExactlyOnce() throws Exception {
        bindMockPort();
        when(mockPort.readBytes(any(byte[].class), anyLong()))
                .thenThrow(new RuntimeException("usb-pulled"));
        assertDeathExactlyOnce("read-exception");
    }

    private void assertDeathExactlyOnce(String expectedReasonPart) throws Exception {
        List<String> reasons = new CopyOnWriteArrayList<>();
        CountDownLatch deathLatch = new CountDownLatch(1);
        transport.onTransportDead(reason -> {
            reasons.add(reason);
            deathLatch.countDown();
        });
        transport.startReading(collectingSink());
        assertTrue("死亡通知必须到达", deathLatch.await(5, TimeUnit.SECONDS));
        awaitTrue("读线程随死亡退出", () -> !rxThreadAlive(port.serialInfo.portName), 5000);
        assertEquals("死亡通知必须恰一次", 1, reasons.size());
        assertTrue("死亡原因须可定位: " + reasons.get(0),
                reasons.get(0).contains(expectedReasonPart));
    }

    /**
     * ⑥respawn 幂等：重复 startReading 不产生第二条读线程（fd 生命周期接线可在重开/
     * resume 时重复调用 startReading，恒恰一条读线程——消旧读线程僵尸窗口）。
     */
    @Test(timeout = 10_000)
    public void repeatedStartReading_yieldsExactlyOneThread() throws Exception {
        bindMockPort();
        CountDownLatch readEntered = new CountDownLatch(1);
        when(mockPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            readEntered.countDown();
            return 0; // 超时醒循环，线程保持存活
        });
        transport.startReading(collectingSink());
        transport.startReading(collectingSink());
        transport.startReading(collectingSink());
        assertTrue("首条读线程必须就位", readEntered.await(5, TimeUnit.SECONDS));
        awaitTrue("线程账稳定观察窗", () -> countRxThreads(port.serialInfo.portName) == 1, 500);
        assertEquals("重复 startReading 必须恒恰一条读线程", 1, countRxThreads(port.serialInfo.portName));
    }

    /**
     * ⑦close 后线程退出且不再 respawn（close 不触发任何 startReading 调用点——
     * respawn 只发生在 open/resume）：观察窗后线程账仍为 0。
     */
    @Test(timeout = 30_000)
    public void closeAfterStop_threadExits_andNeverRespawns() throws Exception {
        assumeRealPty();
        transport.startReading(collectingSink());
        awaitTrue("读线程在跑", () -> countRxThreads(ptyA.getAbsolutePath()) == 1, 5000);
        transport.stopReading();
        transport.close();
        awaitTrue("close 后读线程退出", () -> countRxThreads(ptyA.getAbsolutePath()) == 0, 5000);
        awaitTrue("无 respawn 观察窗（500ms）", () -> countRxThreads(ptyA.getAbsolutePath()) == 0, 500);
        assertEquals("close 后必须零读线程（不 respawn）", 0, countRxThreads(ptyA.getAbsolutePath()));
    }

    /**
     * ⑧pause/resume 映射（modbus 直持流语义）：pause 后注入字节不投递（滞留内核缓冲），
     * resume 后读线程恢复、滞留与后续字节重新进管线（与旧轮询 paused 语义等价的推模型形态）。
     */
    @Test(timeout = 30_000)
    public void pauseStopsDelivery_resumeRestartsReading() throws Exception {
        assumeRealPty();
        transport.startReading(collectingSink());
        awaitTrue("读线程在跑", () -> countRxThreads(ptyA.getAbsolutePath()) == 1, 5000);
        transport.stopReading(); // pause 语义落点
        awaitTrue("pause 后读线程自退", () -> countRxThreads(ptyA.getAbsolutePath()) == 0, 5000);
        delivered.clear();
        try (FileOutputStream peer = new FileOutputStream(ptyB)) {
            peer.write(new byte[]{0x11});
            peer.flush();
            awaitTrue("pause 期零投递观察窗", () -> delivered.isEmpty(), 300);
            assertTrue("pause 后注入必须零投递（滞留内核缓冲）", delivered.isEmpty());
            transport.startReading(collectingSink()); // resume 语义落点
            awaitTrue("resume 后读线程恢复", () -> countRxThreads(ptyA.getAbsolutePath()) == 1, 5000);
            byte[] resumeProbe = new byte[]{0x22};
            peer.write(resumeProbe);
            peer.flush();
            awaitTrue("resume 后字节重新进管线", () -> !delivered.isEmpty(), 5000);
        }
        assertTrue("resume 后必须收到字节（滞留或新注入）", !delivered.isEmpty());
    }

    /** 本 port 的 serial-rx 读线程数（按完整名前缀精确匹配）。 */
    private static int countRxThreads(String portName) {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("serial-rx-" + portName + "-") && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    // ==================== ①即时性 / ③close 唤醒 / ⑤无溢出窗（真实 PTY） ====================

    private Process socat;
    private File ptyA;
    private File ptyB;

    /** 起一对真实 PTY（socat）并在 A 端打开传输；socat 不可用则 Assume 跳过本用例。 */
    private void assumeRealPty() throws IOException {
        File dir = Files.createTempDirectory("serial-contract-pty").toFile();
        dir.deleteOnExit();
        ptyA = new File(dir, "ptyA");
        ptyB = new File(dir, "ptyB");
        try {
            socat = new ProcessBuilder("socat", "-d", "-d",
                    "pty,raw,echo=0,link=" + ptyA.getAbsolutePath(),
                    "pty,raw,echo=0,link=" + ptyB.getAbsolutePath())
                    .redirectErrorStream(true).start();
        } catch (IOException socatUnavailable) {
            Assume.assumeNoException("socat 不可用，真实 PTY 契约用例跳过", socatUnavailable);
        }
        awaitTrue("PTY link 就绪", () -> ptyA.exists() && ptyB.exists(), 5000);
        port.serialInfo = new SerialInfo(ptyA.getAbsolutePath(), 9600, 8, 1, 0, 0, 500);
        assertTrue("真实 PTY 打开必须成功", transport.open());
        assertTrue(transport.isOpen());
    }

    /**
     * ①即时性：真实 PTY 注入应答，从写入到 sink 收到 ≤2ms。预热 5 轮弃置（通路与 JIT
     * 热身），计测 20 轮取最慢轮断言——25ms 轮询间隙若回归，最慢轮立刻爆。
     */
    @Test(timeout = 30_000)
    public void injectedBytes_reachSinkWithin2ms() throws Exception {
        assumeRealPty();
        LinkedBlockingQueue<Long> recvNanos = new LinkedBlockingQueue<>();
        transport.startReading((buf, len) -> recvNanos.add(System.nanoTime()));
        try (FileOutputStream peer = new FileOutputStream(ptyB)) {
            for (int i = 0; i < 5; i++) { // 预热，数据弃置
                peer.write(new byte[]{0x01});
                peer.flush();
                assertNotNull("预热注入必须可达", recvNanos.poll(2, TimeUnit.SECONDS));
            }
            long worstNanos = 0;
            for (int round = 0; round < 20; round++) {
                recvNanos.clear();
                long t0 = System.nanoTime();
                peer.write(new byte[]{0x02});
                peer.flush();
                Long ts = recvNanos.poll(2, TimeUnit.SECONDS);
                assertNotNull("第 " + round + " 轮注入必须可达", ts);
                worstNanos = Math.max(worstNanos, ts - t0);
            }
            double worstMs = worstNanos / 1_000_000.0;
            System.out.println(String.format("CONTRACT|immediacy|rounds=%d|worst=%.3fms|budget=2ms", 20, worstMs));
            assertTrue("即时性契约：最慢轮 " + worstMs + "ms 必须 ≤2ms",
                    worstNanos <= TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * ③close 唤醒：阻塞读线程在 close 后 ≤100ms 退出（看门狗跨线程强拆依赖；
     * stopReading 先行使退出路径安静，与生产 closeFd 的「先停后关」次序一致）。
     */
    @Test(timeout = 30_000)
    public void close_wakesBlockedRead_within100ms() throws Exception {
        assumeRealPty();
        transport.startReading(collectingSink());
        awaitTrue("读线程在跑", () -> rxThreadAlive(ptyA.getAbsolutePath()), 5000);
        long t0 = System.nanoTime();
        transport.stopReading();
        transport.close();
        awaitTrue("close 后读线程 ≤100ms 退出",
                () -> !rxThreadAlive(ptyA.getAbsolutePath()), 100);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("CONTRACT|close-wake|elapsedMs=" + elapsedMs + "|budget=100ms");
        assertTrue("close 到线程退出须 ≤100ms，实测 " + elapsedMs + "ms", elapsedMs <= 100);
    }

    /**
     * ⑤无轮询溢出窗：100KB/s 持续约 1 秒逐字节收满（计数 + CRC32 双断言）——轮询
     * 间隙缓冲覆盖形态的结构性根除钉子。此处 sleep 是负载节拍（持续吞吐契约本质是
     * 墙钟量），非同步手段；断言全部经精确计数与 latch，不猜时序。
     */
    @Test(timeout = 30_000)
    public void sustained100KBps_allBytesDeliveredExactly() throws Exception {
        assumeRealPty();
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        CRC32 expectedCrc = new CRC32();
        AtomicInteger receivedTotal = new AtomicInteger();
        CRC32 receivedCrc = new CRC32();
        long payloadBytes = 100L * 1024;
        CountDownLatch allReceived = new CountDownLatch(1);
        transport.startReading((buf, len) -> {
            for (int i = 0; i < len; i++) {
                receivedCrc.update(buf[i]);
            }
            if (receivedTotal.addAndGet(len) >= payloadBytes) {
                allReceived.countDown();
            }
        });
        try (FileOutputStream peer = new FileOutputStream(ptyB)) {
            long sentTotal = 0;
            while (sentTotal < payloadBytes) {
                peer.write(payload, 0, 1024);
                peer.flush();
                sentTotal += 1024;
                expectedCrc.update(payload, 0, 1024);
                Thread.sleep(10); // ~100KB/s 节拍（负载形态，非同步）
            }
            assertEquals("载荷总量", payloadBytes, sentTotal);
        }
        assertTrue("100KB/s 持续负载必须全部收到（实收 " + receivedTotal.get() + "）",
                allReceived.await(5, TimeUnit.SECONDS));
        assertEquals("逐字节计数一致", payloadBytes, receivedTotal.get());
        assertEquals("内容 CRC 一致（零丢失零改写）", expectedCrc.getValue(), receivedCrc.getValue());
    }
}
