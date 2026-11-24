package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.TcpIntegration.config.TcpConfig;

/**
 * {@link TcpTransport} 契约测试（本地 loopback ServerSocket 充当串口服务器；真实 TCP 栈，
 * 零 mock）。确定性纪律：事件断言全部经队列计数/latch 与 FIFO 因果序；静默断言用有界
 * 安静窗（轮询步进 5ms，非 sleep 猜测）。
 *
 * <p>钉住的契约（§3.4 重连分工：连接重连归 tcp 层，对话层排空归执行者）：
 * <ol>
 *   <li>开连接即收：open 后注入字节 → sink 收到逐字一致内容；</li>
 *   <li>投递门：stopReading 后注入 → 零投递（FIFO 因果锚：复开后新块先于被丢块出现即证
 *       丢弃已发生，非滞留）；startReading 幂等、未 start 时 stop 是 no-op；</li>
 *   <li>写真完成：write 返回时字节已写出 socket（服务端逐字读获）；断线在飞写得释返 -1
 *       且不触 send 的内联重连（重连分工归 tcp 层自动重连）；</li>
 *   <li>断线 → 链路断开通知恰一次；管理面仍 open（tcp 层自动重连中）；断连写 -1 不追加
 *       通知；重连成功自动恢复收发（下一对话正常，恢复后首块=恢复后注入字节——迟到字节
 *       不进新对话）；再次断线 → 再次通知恰一次（每场断线一次，非终死语义）；</li>
 *   <li>重连参数来自 cfg（非硬编码）：maxReconnectAttempts=0 ⇒ 断线后无自动重连（参数
 *       可表达性）；reconnectTimeout 决定重连节拍（到达时刻下界）；</li>
 *   <li>close 幂等（第二次 false）、close 后可重开新连接、close 收场不发断开通知；</li>
 *   <li>首连失败如实返 false 且不留重试链泄漏；</li>
 *   <li>onData 回调管线零固有阻塞（百块突发按计数+间隔界达 sink）。</li>
 * </ol>
 *
 * @author coffee
 */
public class TcpTransportContractTest {

    private ServerSocket server;
    private Acceptor acceptor;
    private final java.util.List<Socket> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
    private TcpTransport transport;

    @Before
    public void setUp() throws IOException {
        server = new ServerSocket(0);
        acceptor = new Acceptor(server);
        transport = new TcpTransport("127.0.0.1", server.getLocalPort(), "tcp-contract-test",
                reconnectConfig(3, 300));
    }

    @After
    public void tearDown() {
        if (transport != null) {
            transport.close();
        }
        for (Socket socket : opened) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 对端已关：关闭清理的常规形态，无账可记
            }
        }
        acceptor.stop();
    }

    /** 测试档 TcpConfig（显式全参，无隐默认）：短重连节拍使断线恢复用例秒级收敛。 */
    private static TcpConfig reconnectConfig(int maxAttempts, int reconnectTimeoutMs) {
        return TcpConfig.builder()
                .host("127.0.0.1")
                .maxReconnectAttempts(maxAttempts)
                .reconnectTimeout(Duration.ofMillis(reconnectTimeoutMs))
                .connectionTimeout(Duration.ofSeconds(2))
                .build();
    }

    private ReadSink collectingSink(LinkedBlockingQueue<byte[]> queue) {
        return (buf, len) -> queue.add(len == buf.length ? buf : Arrays.copyOf(buf, len));
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

    /**
     * 安静窗断言：窗内 condition 任何一次为真即 fail（反证「无后台动作」的唯一确定性形态
     * ——窗长须覆盖被证机制的重试节拍），窗满静默即过。
     */
    private static void awaitQuiet(String what, BooleanSupplier violation, long quietWindowMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(quietWindowMs);
        while (System.nanoTime() < deadline) {
            if (violation.getAsBoolean()) {
                fail("violation observed within quiet window: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted during quiet window: " + what);
            }
        }
    }

    /** 服务端收一线连接（后台 acceptor 承载，此调用=等待下一线到达，2s 界）。 */
    private Socket acceptConnection(String what) {
        Socket socket = acceptor.take(2000);
        opened.add(socket);
        return socket;
    }

    private static <T> T failInterrupted(String what) {
        fail("interrupted while awaiting: " + what);
        return null; // 不可达
    }

    /** 服务端读满 n 字节（读线程承载的阻塞读的同步等价：write 真完成语义保证可读）。 */
    private static byte[] readExactly(Socket socket, int n) throws IOException, InterruptedException {
        InputStream in = socket.getInputStream();
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(out, off, n - off);
            if (read < 0) {
                fail("peer closed before " + n + " bytes (got " + off + ")");
            }
            off += read;
        }
        return out;
    }

    /** 累收内容与期望逐字一致（分块形态无关：按拼接比较）。 */
    private static void assertDeliveredEquals(LinkedBlockingQueue<byte[]> queue, byte[] expected, String what) {
        byte[] joined = new byte[expected.length];
        int off = 0;
        try {
            while (off < expected.length) {
                byte[] chunk = queue.take();
                int copy = Math.min(chunk.length, joined.length - off);
                System.arraycopy(chunk, 0, joined, off, copy);
                off += copy;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while collecting: " + what);
        }
        assertTrue(what + "：内容逐字不一致", Arrays.equals(expected, joined));
    }

    // ==================== ①开连接即收 ====================

    @Test
    public void openConnectsAndDeliversBytesToSink() throws Exception {
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        assertTrue(transport.isOpen());
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading(collectingSink(delivered));

        byte[] payload = "RTU-OVER-TCP-HELLO".getBytes("UTF-8");
        OutputStream out = peer.getOutputStream();
        out.write(payload);
        out.flush();

        awaitTrue("bytes reach sink", () -> totalQueued(delivered) >= payload.length, 2000);
        assertDeliveredEquals(delivered, payload, "open 后注入字节");
    }

    // ==================== ②投递门（stopReading 丢弃 + 幂等/no-op） ====================

    @Test
    public void stopReadingDiscardsLateBytesAndResumeDeliversOnlyNew() throws Exception {
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading(collectingSink(delivered));
        OutputStream out = peer.getOutputStream();

        byte[] first = "A".getBytes("UTF-8");
        out.write(first);
        out.flush();
        awaitTrue("first chunk reaches sink", () -> totalQueued(delivered) >= 1, 2000);

        transport.stopReading();
        byte[] dropped = "BC".getBytes("UTF-8");
        out.write(dropped);
        out.flush();

        // 第一段（缺席证明）：安静窗内 B/C 若被误投递即当场红——窗满零投递即证丢弃路径生效
        awaitQuiet("stopped gate must not deliver", () -> totalQueued(delivered) >= 2, 200);

        // 第二段（FIFO 因果锚）：复开后注入 D——若 B/C 被滞留而非丢弃，D 之前必先出现 B/C；
        // 队列恰为 A+D 即证丢弃已发生
        transport.startReading(collectingSink(delivered));
        byte[] afterResume = "D".getBytes("UTF-8");
        out.write(afterResume);
        out.flush();

        awaitTrue("post-resume chunk reaches sink", () -> totalQueued(delivered) >= 2, 2000);
        assertEquals("stopReading 期字节必须被丢弃（不得滞留后补投）", 2, totalQueued(delivered));
        assertDeliveredEquals(delivered, "AD".getBytes("UTF-8"), "投递门因果序");
    }

    @Test
    public void startReadingIdempotentAndStopBeforeStartIsNoop() throws Exception {
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        // 未 start 即 stop：no-op 不抛（缝契约 5）
        transport.stopReading();
        transport.startReading(collectingSink(delivered));
        transport.startReading(collectingSink(delivered)); // 幂等：重复 start 无副作用

        byte[] payload = "IDEMPOTENT".getBytes("UTF-8");
        OutputStream out = peer.getOutputStream();
        out.write(payload);
        out.flush();
        awaitTrue("bytes reach sink after repeated start", () -> totalQueued(delivered) >= payload.length, 2000);
        assertDeliveredEquals(delivered, payload, "重复 startReading 后投递正常");
    }

    // ==================== ③写真完成 + 断线在飞写得释 ====================

    @Test
    public void writeCompletesOnSocketAndEchoReturnsThroughSink() throws Exception {
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading(collectingSink(delivered));

        byte[] command = "POLL-01-04".getBytes("UTF-8");
        int written = transport.write(command, command.length);
        assertEquals("写出字节数", command.length, written);

        // 真完成：write 返回时服务端已可逐字读获
        byte[] seen = readExactly(peer, command.length);
        assertTrue("服务端逐字一致", Arrays.equals(command, seen));

        byte[] reply = "REPLY-OK".getBytes("UTF-8");
        OutputStream out = peer.getOutputStream();
        out.write(reply);
        out.flush();
        awaitTrue("echo reaches sink", () -> totalQueued(delivered) >= reply.length, 2000);
        assertDeliveredEquals(delivered, reply, "回程经 onData→sink");
    }

    @Test(timeout = 10_000)
    public void inFlightWriteIsReleasedMinusOneOnDisconnectWithoutInlineReconnect() throws Exception {
        LinkedBlockingQueue<String> linkDowns = new LinkedBlockingQueue<>();
        transport.onTransportDead(linkDowns::add);
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading(collectingSink(delivered));

        // 在飞写挂死构造：16MB payload（大于内核发送缓冲自动调优上限）+ 服务端不读 →
        // selector 不可写，send 挂在真完成等待上（模拟执行者对话 AWAIT_WRITE 卡写）；对端
        // 断线 → tcp 连接层 failPendingWrites 异常完成 → write 得释返 -1（对话失败收场，
        // 非阻塞泄漏）
        byte[] bigPayload = new byte[16 * 1024 * 1024];
        Arrays.fill(bigPayload, (byte) 0x5A);
        AtomicInteger writeResult = new AtomicInteger(Integer.MIN_VALUE);
        Thread writer = new Thread(() -> writeResult.set(transport.write(bigPayload, bigPayload.length)),
                "tcp-contract-inflight-writer");
        writer.start();
        awaitTrue("write is blocked in flight", () -> writer.getState() == Thread.State.WAITING, 5000);

        peer.close();
        writer.join(5000);
        assertFalse("在飞写须随断线得释（join 超时即挂死）", writer.isAlive());
        assertEquals("断线在飞写必须 -1（对话失败收场）", -1, writeResult.get());

        // 链路断开通知恰一次（断线事件承载，写失败路径不追加）
        awaitTrue("link-down notified", () -> !linkDowns.isEmpty(), 2000);
        assertEquals("断线通知恰一次", 1, linkDowns.size());

        // 断连写：-1 快速失败；不追加通知；不触 send 内联重连（重连分工归 tcp 层自动重连）
        byte[] stale = "STALE".getBytes("UTF-8");
        assertEquals("断连写必须 -1（不内联重连）", -1, transport.write(stale, stale.length));
        assertEquals("断连写不再追加断开通知（每场断线恰一次）", 1, linkDowns.size());
    }

    // ==================== ④断线自动重连 + 恢复语义（§3.4） ====================

    @Test
    public void linkDownNotifiesOnce_thenTcpLayerAutoReconnectsAndResumesDelivery() throws Exception {
        LinkedBlockingQueue<String> linkDowns = new LinkedBlockingQueue<>();
        transport.onTransportDead(linkDowns::add);
        assertTrue(transport.open());
        Socket firstPeer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        transport.startReading(collectingSink(delivered));
        OutputStream firstOut = firstPeer.getOutputStream();

        // 断线前字节全部消费完（因果锚前提：sink 流中不存在「断线前未消费」残留——
        // 到达即消费断言，队列清零后才开始断线）
        byte[] beforeDown = "PRE".getBytes("UTF-8");
        firstOut.write(beforeDown);
        firstOut.flush();
        awaitTrue("pre-down bytes arrived", () -> totalQueued(delivered) >= beforeDown.length, 2000);
        assertDeliveredEquals(delivered, beforeDown, "断线前注入逐字到达");

        // 断线：链路断开通知恰一次；管理面仍 open（tcp 层自动重连中——isOpen 不随数据面抖动）
        long downAtNanos = System.nanoTime();
        firstPeer.close();
        awaitTrue("link-down notified once", () -> linkDowns.size() >= 1, 2000);
        assertEquals("断线通知恰一次", 1, linkDowns.size());
        assertEquals("断线原因=disconnected", "disconnected", linkDowns.peek());
        assertTrue("断线后管理面仍 open（重连归 tcp 层，非终死）", transport.isOpen());

        // tcp 层自动重连自动到达（无需显式 reopen；reconnectTimeout=300ms 节拍）
        Socket reconnected = acceptConnection("tcp-layer auto reconnect");

        // 恢复确认探针（确定性时序锚）：write→服务端读获→回写 ACK→sink 收到 ACK。accept
        // 返回早于客户端 connectInternal 完成注册（内核握手先于应用层 connected 置位），
        // 探针 write 以有界轮询等数据面就绪（write -1 无副作用，安全重试）——就绪后必成功。
        // ACK 投递成功即证链路门已复开（linkUp=true 在车道序上先于 ACK），此后注入的 POST
        // 必然落在门开窗内——消除「accept 早于 RECONNECT_SUCCEEDED 车道处理」的注入竞态
        byte[] probe = "PROBE".getBytes("UTF-8");
        awaitTrue("probe write succeeds after data-plane ready",
                () -> transport.write(probe, probe.length) == probe.length, 2000);
        assertTrue(Arrays.equals(probe, readExactly(reconnected, probe.length)));
        byte[] ack = "ACK".getBytes("UTF-8");
        OutputStream reOut = reconnected.getOutputStream();
        reOut.write(ack);
        reOut.flush();
        awaitTrue("recovery probe ACK arrives", () -> totalQueued(delivered) >= ack.length, 2000);
        assertDeliveredEquals(delivered, ack, "恢复探针 ACK 逐字到达（链路门已复开）");

        // 恢复后注入（迟到字节因果锚）：恢复后流中无任何断线窗字节——断线窗口字节被链路门
        // 丢弃、断线前残留已消费完毕，POST 之前不可能出现旧连接字节
        byte[] afterReconnect = "POST".getBytes("UTF-8");
        reOut.write(afterReconnect);
        reOut.flush();
        awaitTrue("post-reconnect chunk arrives", () -> totalQueued(delivered) >= afterReconnect.length, 2000);
        assertDeliveredEquals(delivered, afterReconnect, "恢复后流恰为恢复后注入（迟到字节不进新对话）");
        long reconnectArrivalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downAtNanos);
        assertTrue("重连到达过快（节拍未走 reconnectTimeout，elapsed=" + reconnectArrivalMs + "ms）",
                reconnectArrivalMs >= 250);

        // 恢复后下一对话正常（写读双向全通）
        byte[] fresh = "FRESH".getBytes("UTF-8");
        assertEquals("恢复后写出字节数", fresh.length, transport.write(fresh, fresh.length));
        assertTrue(Arrays.equals(fresh, readExactly(reconnected, fresh.length)));
    }

    @Test
    public void secondLinkDownNotifiesAgain_perDownCycleOnceNotLifetimeOnce() throws Exception {
        LinkedBlockingQueue<String> linkDowns = new LinkedBlockingQueue<>();
        transport.onTransportDead(linkDowns::add);
        assertTrue(transport.open());
        Socket firstPeer = acceptConnection("initial connect");

        // 第一场断线：通知恰一次；tcp 层自动重连恢复
        firstPeer.close();
        awaitTrue("first link-down", () -> linkDowns.size() >= 1, 2000);
        Socket reconnected = acceptConnection("first auto reconnect");

        // 第二场断线：再次通知（每场断线恰一次，非终身一次）——终死语义已退役（§3.4）
        reconnected.close();
        awaitTrue("second link-down", () -> linkDowns.size() >= 2, 2000);
        assertEquals("两场断线各通知恰一次", 2, linkDowns.size());
        acceptConnection("second auto reconnect");
    }

    // ==================== ⑤重连参数来自 cfg（非硬编码） ====================

    @Test
    public void reconnectParamsComeFromConfig_zeroAttemptsSuppressesAutoReconnect() throws Exception {
        TcpTransport suppressed = new TcpTransport("127.0.0.1", server.getLocalPort(),
                "tcp-suppressed-test", reconnectConfig(0, 300));
        final LinkedBlockingQueue<String> suppressedDowns = new LinkedBlockingQueue<>();
        suppressed.onTransportDead(suppressedDowns::add);
        try {
            assertTrue(suppressed.open());
            Socket peer = acceptConnection("suppressed initial connect");
            peer.close();
            awaitTrue("link-down notified", () -> !suppressedDowns.isEmpty(), 2000);

            // maxReconnectAttempts=0 ⇒ 无自动重连（参数可表达性 = P4 抑制语义的 cfg 面）：
            // 安静窗覆盖原节拍（300ms）+ 余量，Acceptor 计数静止即证参数生效
            awaitQuiet("zero attempts must suppress auto-reconnect",
                    () -> acceptor.count() > 1, 1400);
        } finally {
            suppressed.close();
        }
    }

    @Test
    public void reconnectParamsComeFromConfig_reconnectTimeoutGovernsRetryBeat() throws Exception {
        // 1s 节拍档：断线后重连不得在节拍窗内提前到达（schedule 单向误差——只会更晚不会更早）
        TcpTransport slowBeat = new TcpTransport("127.0.0.1", server.getLocalPort(),
                "tcp-slow-beat-test", reconnectConfig(3, 1000));
        try {
            assertTrue(slowBeat.open());
            Socket peer = acceptConnection("slow-beat initial connect");
            long downAtNanos = System.nanoTime();
            peer.close();

            awaitQuiet("reconnect must not arrive before reconnectTimeout elapses",
                    () -> acceptor.count() > 1, 700);
            acceptor.take(3000);
            long arrivalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downAtNanos);
            assertTrue("1s 节拍档重连到达过晚（elapsed=" + arrivalMs + "ms）", arrivalMs < 3500);
        } finally {
            slowBeat.close();
        }
    }

    // ==================== ⑥close 幂等 + 重开 + close 不发断开通知 ====================

    @Test
    public void closeIsIdempotentAndReopenStartsFreshCycle() {
        assertTrue(transport.open());
        acceptConnection("initial connect");
        assertTrue("首场 close 应执行了关闭", transport.close());
        assertFalse("第二场 close 应是 no-op", transport.close());
        assertFalse(transport.isOpen());

        assertTrue("close 后重开", transport.open());
        acceptConnection("reopen connect");
        assertTrue(transport.isOpen());
    }

    @Test
    public void closeAfterLinkDownDoesNotFireLinkDownNotification() throws Exception {
        LinkedBlockingQueue<String> linkDowns = new LinkedBlockingQueue<>();
        transport.onTransportDead(linkDowns::add);
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");

        // 断线通知一次 → close 收场（release 幂等且不发 DISCONNECTED）：无追加通知
        peer.close();
        awaitTrue("link-down notified", () -> !linkDowns.isEmpty(), 2000);
        transport.close();
        awaitQuiet("close must not fire link-down notification",
                () -> linkDowns.size() > 1, 400);
        assertEquals("close 收场零追加断开通知", 1, linkDowns.size());
    }

    // ==================== ⑦首连失败如实返 false + 重试链不泄漏 ====================

    @Test
    public void openToRefusedPortFailsCleanlyWithoutBackgroundRetryLeak() throws Exception {
        // 制造拒绝目标：取空闲端口后关闭监听（connect 得 RST = 快速拒绝）
        ServerSocket probe = new ServerSocket(0);
        int refusedPort = probe.getLocalPort();
        probe.close();

        TcpTransport failing = new TcpTransport("127.0.0.1", refusedPort, "tcp-refused-test",
                reconnectConfig(3, 300));
        final LinkedBlockingQueue<String> failingDowns = new LinkedBlockingQueue<>();
        failing.onTransportDead(failingDowns::add);
        try {
            long start = System.nanoTime();
            assertFalse("拒绝端口 open 必须如实返 false", failing.open());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("拒绝路径应快速判负（elapsed=" + elapsedMs + "ms）", elapsedMs < 4500);
            assertFalse(failing.isOpen());
            assertEquals("首连失败不产生断开通知（未达过已连接态）", 0, failingDowns.size());

            // 重试链不泄漏：目标端口恢复监听后，安静窗（覆盖 TcpConnection 首连重试节拍 1s）
            // 内无任何后台连接出现
            ServerSocket reviverSocket = new ServerSocket(refusedPort);
            Acceptor reviver = new Acceptor(reviverSocket);
            try {
                awaitQuiet("first-connect retry chain must be terminated on open failure",
                        reviver::hasAny, 1500);
                // 失败后 transport 可正常重开（无残留半死状态）
                assertTrue("失败收场后显式 reopen 成功", failing.open());
                Socket peer = reviver.take(2000);
                opened.add(peer);
                byte[] payload = "AFTER-FAIL".getBytes("UTF-8");
                OutputStream out = peer.getOutputStream();
                out.write(payload);
                out.flush();
                LinkedBlockingQueue<byte[]> sinkQueue = new LinkedBlockingQueue<>();
                failing.startReading(collectingSink(sinkQueue));
                awaitTrue("bytes reach sink after reopen", () -> totalQueued(sinkQueue) >= payload.length, 2000);
                assertDeliveredEquals(sinkQueue, payload, "失败后重开收数");
            } finally {
                reviver.stop();
            }
        } finally {
            failing.close();
        }
    }

    // ==================== ⑧onData 回调管线零固有阻塞 ====================

    @Test
    public void onDataPipelineBurstThroughputWithoutInherentBlocking() throws Exception {
        assertTrue(transport.open());
        Socket peer = acceptConnection("initial connect");
        LinkedBlockingQueue<byte[]> sinkQueue = new LinkedBlockingQueue<>();
        AtomicLong lastDeliveryNanos = new AtomicLong(System.nanoTime());
        AtomicInteger maxGapMillis = new AtomicInteger();
        transport.startReading((buf, len) -> {
            long now = System.nanoTime();
            long gap = TimeUnit.NANOSECONDS.toMillis(now - lastDeliveryNanos.getAndSet(now));
            maxGapMillis.accumulateAndGet((int) gap, Math::max);
            sinkQueue.add(len == buf.length ? buf : Arrays.copyOf(buf, len));
        });

        // 百块突发（每块 1KB）：若回调链含任何每块阻塞（如等消费者、锁等待），总时长或
        // 相邻块间隔越界——计数与间隔双断言（无 sleep 猜测）
        int chunks = 100;
        int chunkSize = 1024;
        byte[] chunk = new byte[chunkSize];
        Arrays.fill(chunk, (byte) 'x');
        OutputStream out = peer.getOutputStream();
        long burstStart = System.nanoTime();
        for (int i = 0; i < chunks; i++) {
            out.write(chunk);
            out.flush();
        }
        long totalExpected = chunks * chunkSize;
        awaitTrue("burst fully delivered", () -> totalQueued(sinkQueue) >= totalExpected, 4000);
        long burstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - burstStart);
        assertTrue("突发吞吐异常回调阻塞（total=" + burstMs + "ms）", burstMs < 3000);
        assertTrue("相邻投递间隔异常（maxGap=" + maxGapMillis.get() + "ms）", maxGapMillis.get() < 500);
    }

    // ==================== 辅助 ====================

    private static int totalQueued(LinkedBlockingQueue<byte[]> queue) {
        int total = 0;
        for (byte[] chunk : queue) {
            total += chunk.length;
        }
        return total;
    }

    /**
     * 服务端 accept 承载线程：连接到达即入队计数——「有无新连接」的观测点（自动重连连接
     * 没人 accept 不可见，主线程 accept 会破坏监听器状态；本线程专职承载，quiet 断言按计数）。
     */
    private static final class Acceptor {
        private final ServerSocket listener;
        private final LinkedBlockingQueue<Socket> connections = new LinkedBlockingQueue<>();
        private final Thread thread;

        Acceptor(ServerSocket listener) {
            this.listener = listener;
            this.thread = new Thread(this::loop, "tcp-contract-acceptor");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void loop() {
            while (!listener.isClosed()) {
                try {
                    connections.add(listener.accept());
                } catch (IOException closed) {
                    return; // 监听关闭：线程正常退出
                }
            }
        }

        int count() {
            return connections.size();
        }

        boolean hasAny() {
            return !connections.isEmpty();
        }

        Socket take(long timeoutMs) {
            try {
                Socket socket = connections.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (socket == null) {
                    fail("no incoming connection within " + timeoutMs + "ms");
                }
                return socket;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failInterrupted("incoming connection");
            }
        }

        void stop() {
            try {
                listener.close();
            } catch (IOException ignored) {
                // 已关：清理常规形态
            }
        }
    }
}
