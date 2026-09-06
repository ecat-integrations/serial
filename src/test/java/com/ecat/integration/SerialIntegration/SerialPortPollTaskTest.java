package com.ecat.integration.SerialIntegration;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.fazecast.jSerialComm.SerialPort;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 事件→轮询收敛的轮询任务契约测试（IO 线程收敛调研 B.2 红测试清单固化）。
 *
 * <p>覆盖四组契约：
 * <ol>
 *   <li>轮询路径收帧（socat pty 真口对优先；socat 不可用自动跳过真测部分，
 *       mock 侧契约不受影响）；</li>
 *   <li>帧分片到达（半帧+续帧不丢不重）；</li>
 *   <li>closePort 后 -1 哨兵自取消 + 全生命周期零 waitForEvent 事件线程
 *       （旧事件模式每打开一口产生一根 jSerialComm 库内线程——48 口 48 条的根因）；</li>
 *   <li>轮询异常不杀任务（fixedDelay 未捕获异常即永久停调度的契约反向锁定）、
 *       重复 startPolling 不产生双任务双读、paused 口不被轮询读取（Modbus 语义）。</li>
 * </ol>
 *
 * <p>同步方式：数据到达用 {@link CountDownLatch}，条件状态（future 取消等）用
 * awaitility 式有界条件自旋——验证「事件已发生」，不用固定 sleep 猜测。
 *
 * @author coffee
 */
public class SerialPortPollTaskTest {

    /** 真测等待上限（秒）——确定性等待的保险丝，正常路径毫秒级返回。 */
    private static final int AWAIT_SECONDS = 10;

    private ScheduledExecutorService testScheduler;
    private SerialSourcePort realPortUnderTest;
    private SerialSource realSourceUnderTest;
    private SerialPort peerPort;
    private Process socat;

    @Before
    public void setUp() {
        testScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "serial-poll-test");
            t.setDaemon(true);
            return t;
        });
        SerialPollScheduler.bind(testScheduler);
    }

    @After
    public void tearDown() {
        SerialPollScheduler.unbind();
        testScheduler.shutdownNow();
        if (realSourceUnderTest != null) {
            try {
                realSourceUnderTest.closePort();
            } catch (Exception ignored) {
            }
        }
        if (realPortUnderTest != null && realPortUnderTest.serialPort != null
                && realPortUnderTest.serialPort.isOpen()) {
            try {
                realPortUnderTest.serialPort.closePort();
            } catch (Exception ignored) {
            }
        }
        if (peerPort != null && peerPort.isOpen()) {
            try {
                peerPort.closePort();
            } catch (Exception ignored) {
            }
        }
        if (socat != null) {
            socat.destroy();
        }
    }

    // ==================== 真口对（socat pty）基建 ====================

    /** 用 socat 造一对互联 pty（A=被测端口，B=对端写入口）；socat 不可用则跳过真测。 */
    private Process assumeSocatPair(String tag, String linkA, String linkB) {
        Process process = null;
        try {
            new File(linkA).delete();
            new File(linkB).delete();
            process = new ProcessBuilder("socat", "-d", "-d",
                    "pty,raw,echo=0,link=" + linkA, "pty,raw,echo=0,link=" + linkB)
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            if (process != null) {
                process.destroy();
            }
            Assume.assumeNoException("socat unavailable, skip real-pair part: " + tag, e);
        }
        try {
            awaitTrue("socat pty links appear", () -> new File(linkA).exists() && new File(linkB).exists(), 5000);
        } catch (AssertionError e) {
            process.destroy();
            Assume.assumeTrue("socat pair did not come up, skip real-pair part: " + tag, false);
        }
        return process;
    }

    /**
     * 打开真实端口对中的被测端：openPort（测试模式不自动挂轮询——isTestMode 守卫回归）
     * + 手动 startPolling（生产路径同一方法）。
     */
    private SerialSourcePort openPolledPort(String linkA) {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(linkA, 9600, 8, 1, 0), 1, null);
        SerialSource source = new SerialSource(port, "poll-test");
        assertTrue("port under test should be open", port.isPortOpen());
        assertNull("test mode must not auto-schedule poll (isTestMode guard)",
                port.getPollTaskHandle());
        port.startPolling();
        assertNotNull("poll task should be scheduled after startPolling", port.getPollTaskHandle());
        realPortUnderTest = port;
        realSourceUnderTest = source;
        return port;
    }

    private SerialPort openPeer(String linkB) {
        SerialPort peer = SerialPort.getCommPort(linkB);
        peer.setBaudRate(9600);
        peer.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        assertTrue("peer port open failed: " + linkB, peer.openPort());
        peerPort = peer;
        return peer;
    }

    /** 累积式监听：按字节总数 latch（帧长已知，到达即确定性触发）。 */
    private static class FrameCollector implements SerialDataListener {
        private final StringBuilder received = new StringBuilder();
        private volatile CountDownLatch latch;
        private volatile int expected;

        FrameCollector expectBytes(int expectedBytes) {
            synchronized (received) {
                this.expected = expectedBytes;
                CountDownLatch fresh = new CountDownLatch(1);
                if (received.length() >= expectedBytes) {
                    fresh.countDown();
                }
                this.latch = fresh;
            }
            return this;
        }

        String received() {
            synchronized (received) {
                return received.toString();
            }
        }

        @Override
        public void onDataReceived(byte[] data, int length) {
            CountDownLatch toCount;
            synchronized (received) {
                received.append(new String(data, 0, length));
                toCount = latch;
                if (toCount != null && received.length() >= expected) {
                    toCount.countDown();
                }
            }
        }

        @Override
        public void onError(Exception ex) {
            fail("listener error: " + ex.getMessage());
        }
    }

    // ==================== ① 轮询路径收帧（真口对） ====================

    @Test
    public void pollTask_receivesFullFrame_overRealPtyPair() throws Exception {
        String linkA = "/tmp/ecat-serial-poll-full-a";
        String linkB = "/tmp/ecat-serial-poll-full-b";
        socat = assumeSocatPair("full-frame", linkA, linkB);
        openPolledPort(linkA);
        SerialPort peer = openPeer(linkB);

        String frame = "POLL-FRAME-0123456789ABCDEFG";
        FrameCollector collector = new FrameCollector();
        realSourceUnderTest.addDataListener(collector);
        collector.expectBytes(frame.length());

        assertEquals(frame.length(), peer.writeBytes(frame.getBytes(), frame.length()));

        assertTrue("frame should arrive via polling within deadline",
                collector.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("polled frame content must match", frame, collector.received());

        // 轮询模式收帧全程零 jSerialComm 事件线程（旧事件模式此点已有 1 条 waitForEvent 线程）
        assertEquals("no jSerialComm waitForEvent event thread while polling", 0, jsSerialCommEventThreadCount());
    }

    // ==================== ② 帧分片到达（半帧+续帧） ====================

    @Test
    public void pollTask_reassemblesFragmentedFrame_overRealPtyPair() throws Exception {
        String linkA = "/tmp/ecat-serial-poll-frag-a";
        String linkB = "/tmp/ecat-serial-poll-frag-b";
        socat = assumeSocatPair("fragmented-frame", linkA, linkB);
        openPolledPort(linkA);
        SerialPort peer = openPeer(linkB);

        String full = "FRAGMENTED-FRAME-PAYLOAD-0123456789";
        String firstHalf = full.substring(0, full.length() / 2);
        String secondHalf = full.substring(full.length() / 2);

        FrameCollector collector = new FrameCollector();
        realSourceUnderTest.addDataListener(collector);

        // 半帧先到：轮询必须交付半帧（不等待完整帧才读）
        collector.expectBytes(firstHalf.length());
        assertEquals(firstHalf.length(), peer.writeBytes(firstHalf.getBytes(), firstHalf.length()));
        assertTrue("first fragment should be delivered by polling",
                collector.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("fragment order must be preserved", firstHalf, collector.received());

        // 续帧后到：再交付剩余字节，拼接后不丢不重
        collector.expectBytes(full.length());
        assertEquals(secondHalf.length(), peer.writeBytes(secondHalf.getBytes(), secondHalf.length()));
        assertTrue("second fragment should be delivered by polling",
                collector.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("reassembled frame must equal original", full, collector.received());
    }

    // ==================== ③ closePort 自取消 + 零事件线程 ====================

    @Test
    public void closePort_pollTaskSelfCancelsViaMinusOneSentinel_noEventThreadLeak() throws Exception {
        String linkA = "/tmp/ecat-serial-poll-close-a";
        String linkB = "/tmp/ecat-serial-poll-close-b";
        socat = assumeSocatPair("close-self-cancel", linkA, linkB);
        SerialSourcePort port = openPolledPort(linkA);
        SerialPort peer = openPeer(linkB);

        // 先证收帧通路活着（同一会话内的自证，防「任务从未跑过」的假绿）
        FrameCollector collector = new FrameCollector();
        realSourceUnderTest.addDataListener(collector);
        collector.expectBytes(5);
        peer.writeBytes("ALIVE".getBytes(), 5);
        assertTrue(collector.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        // 绕过 unregisterSource 直接关底层口：模拟 jSerialComm 侧关闭（写反压自动关同类），
        // 轮询任务下一 tick 读到 -1 哨兵必须自取消——不取消即反复轮询死口。
        port.serialPort.closePort();
        ScheduledFuture<?> handle = port.getPollTaskHandle();
        assertNotNull(handle);
        awaitTrue("poll task must self-cancel after bytesAvailable()==-1 sentinel",
                handle::isCancelled, 5000);

        // 全生命周期 open/close 多轮后仍零事件线程（旧事件模式每 open 一次 +1 条）。
        // 显式停止路径（最后一个 source 注销）与重开路径（挂新任务）对称验证。
        realSourceUnderTest.closePort();
        assertNull("last-source close cancels poll task", port.getPollTaskHandle());
        for (int i = 0; i < 3; i++) {
            // 退役门上线（bug-record 214000/103824）后，最后一个 source 注销即退役本对象——
            // 同对象重开被拒。生产的重开路径 = integration.register 经 removePort 除名后命中
            // 新 SerialSourcePort；此处等价复刻：每轮用新端口对象开同一 pty，open/close 契约不变
            // （重开挂新任务 / 最后一个 source 关闭再取消）。
            SerialSourcePort reopenedPort = new SerialSourcePort(new SerialInfo(linkA, 9600, 8, 1, 0), 1, null);
            SerialSource reopened = new SerialSource(reopenedPort, "reopen-" + i);
            assertTrue("fresh port object should open on live pty", reopenedPort.isPortOpen());
            reopenedPort.startPolling();
            assertNotNull("reopen schedules fresh poll task", reopenedPort.getPollTaskHandle());
            reopened.closePort();
            assertNull("close after reopen cancels poll task again", reopenedPort.getPollTaskHandle());
        }
        assertEquals("no jSerialComm waitForEvent thread after open/close cycles",
                0, jsSerialCommEventThreadCount());
    }

    // ==================== ④ 异常不杀任务 / 不重复注册 / paused 语义 ====================

    /**
     * 轮询读异常（口被拔等传输层异常）不得杀死任务：fixedDelay 契约下未捕获异常即永久停调度，
     * 任务必须自捕获并放行下一 tick——第二 tick 仍能收到数据。
     */
    @Test
    public void pollException_doesNotKillTask_nextTickStillReceives() throws Exception {
        SerialSourcePort port = portWithMockSerialPort();
        final byte[] payload = "AFTER-GLITCH".getBytes();

        AtomicInteger availableCalls = new AtomicInteger();
        when(port.serialPort.bytesAvailable()).thenAnswer(inv -> {
            int n = availableCalls.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("simulated transport glitch (unplugged)");
            }
            return n == 2 ? payload.length : 0;
        });
        when(port.serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            int length = (int) (long) inv.getArgument(1);
            System.arraycopy(payload, 0, buffer, 0, length);
            return length;
        });

        CountDownLatch received = new CountDownLatch(1);
        realSourceUnderTest.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                received.countDown();
            }

            @Override
            public void onError(Exception ex) {
                fail("unexpected listener error: " + ex.getMessage());
            }
        });

        port.startPolling();
        assertTrue("task must survive first-tick exception and receive on next tick",
                received.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        verify(port.serialPort, times(1)).readBytes(any(byte[].class), anyLong());
    }

    /**
     * 重复 startPolling 不产生双任务：旧任务必须被取消（单口单任务），
     * 换新任务后数据仍恰好交付一次（无双读）。
     */
    @Test
    public void repeatedStartPolling_cancelsOldTask_noDoubleRead() throws Exception {
        SerialSourcePort port = portWithMockSerialPort();
        final byte[] payload = "ONCE-ONLY".getBytes();

        AtomicInteger availableCalls = new AtomicInteger();
        when(port.serialPort.bytesAvailable()).thenAnswer(inv -> {
            int n = availableCalls.incrementAndGet();
            return n == 1 ? payload.length : 0;
        });
        when(port.serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            int length = (int) (long) inv.getArgument(1);
            System.arraycopy(payload, 0, buffer, 0, length);
            return length;
        });

        CountDownLatch received = new CountDownLatch(1);
        realSourceUnderTest.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                received.countDown();
            }

            @Override
            public void onError(Exception ex) {
                fail("unexpected listener error: " + ex.getMessage());
            }
        });

        port.startPolling();
        assertTrue("first task delivers payload once",
                received.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        // 重挂新任务（写反压重开/并发注册同路径）：旧任务取消，新任务接管；
        // 让新任务空转 ≥20 个 tick（bytesAvailable 计数推进为正事件），readBytes 仍只有 1 次。
        int callsBeforeRestart = availableCalls.get();
        port.startPolling();
        assertNotNull(port.getPollTaskHandle());
        awaitTrue("second task must tick at least 20 times",
                () -> availableCalls.get() - callsBeforeRestart >= 20, 5000);
        verify(port.serialPort, times(1)).readBytes(any(byte[].class), anyLong());
    }

    /** 重复 startPolling 的取消契约（纯确定性，不经调度器时序）：旧句柄必被 cancel。 */
    @SuppressWarnings("unchecked")
    @Test
    public void startPolling_cancelsPreviousHandle_deterministic() {
        SerialSourcePort port = portWithMockSerialPort();
        try {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            ScheduledFuture<?> first = mock(ScheduledFuture.class);
            ScheduledFuture<?> second = mock(ScheduledFuture.class);
            doReturn(first, second).when(mockScheduler).scheduleWithFixedDelay(any(Runnable.class),
                    eq(SerialSourcePort.POLL_PERIOD_MS), eq(SerialSourcePort.POLL_PERIOD_MS), eq(TimeUnit.MILLISECONDS));
            SerialPollScheduler.bind(mockScheduler);

            port.startPolling();
            port.startPolling();

            verify(mockScheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class),
                    eq(SerialSourcePort.POLL_PERIOD_MS), eq(SerialSourcePort.POLL_PERIOD_MS), eq(TimeUnit.MILLISECONDS));
            verify(first).cancel(false);
            verify(second, never()).cancel(anyBoolean());
        } finally {
            SerialPollScheduler.unbind();
            SerialPollScheduler.bind(testScheduler);
        }
    }

    /** paused 口（Modbus 直持 InputStream 期间）不被轮询读取：volatile 读让路，不碰 syscall。 */
    @Test
    public void pausedPort_pollTaskSkipsRead_modbusSemantics() {
        SerialSourcePort port = portWithMockSerialPort();
        port.pauseEventAdapter();

        SerialPortPollTask task = new SerialPortPollTask(port);
        task.run();
        task.run();

        verify(port.serialPort, never()).bytesAvailable();
        verify(port.serialPort, never()).readBytes(any(byte[].class), anyLong());
    }

    /** paused → resume 后轮询恢复读取（真口对正路径）。 */
    @Test
    public void pausedThenResumed_pollResumesDelivery_overRealPtyPair() throws Exception {
        String linkA = "/tmp/ecat-serial-poll-pause-a";
        String linkB = "/tmp/ecat-serial-poll-pause-b";
        socat = assumeSocatPair("pause-resume", linkA, linkB);
        SerialSourcePort port = openPolledPort(linkA);
        SerialPort peer = openPeer(linkB);

        port.pauseEventAdapter();
        FrameCollector collector = new FrameCollector();
        realSourceUnderTest.addDataListener(collector);
        collector.expectBytes(6);
        peer.writeBytes("PAUSED".getBytes(), 6);

        // 暂停期间数据滞留 OS 缓冲（负向验证见 mock 侧 pausedPort_pollTaskSkipsRead_modbusSemantics）；
        // 恢复后必须把滞留数据读走并交付——证明 pause 是「让路」不是「丢弃」。
        port.resumeEventAdapter();
        assertTrue("buffered bytes must be drained after resume",
                collector.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("PAUSED", collector.received());
    }

    // ==================== ⑤ 生产路径承载线程（103000：数据面自持 sweeper） ====================

    /**
     * 生产路径（未 bind）轮询必须跑在自持单线程 serial-io-sweeper 上：
     * 数据投递与业务引擎 worker 分离——103000 归因证明共享引擎被阻塞任务钉死时
     * 端口轮询随之停摆（Mode B），自持线程还原 Mode A。
     */
    @Test
    public void productionPath_pollRunsOnSweeperThread_singleThreadForAllPorts() throws Exception {
        SerialPollScheduler.unbind();
        try {
            SerialSourcePort portA = portWithMockSerialPort();
            final byte[] payload = "SWEEPER".getBytes();
            when(portA.serialPort.bytesAvailable()).thenReturn(payload.length);
            when(portA.serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
                byte[] buffer = inv.getArgument(0);
                int length = (int) (long) inv.getArgument(1);
                System.arraycopy(payload, 0, buffer, 0, length);
                return length;
            });

            Set<String> deliveryThreads = ConcurrentHashMap.newKeySet();
            CountDownLatch delivered = new CountDownLatch(1);
            realSourceUnderTest.addDataListener(new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    deliveryThreads.add(Thread.currentThread().getName());
                    delivered.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    fail("unexpected listener error: " + ex.getMessage());
                }
            });

            portA.startPolling();
            assertTrue("payload must be delivered via production scheduler",
                    delivered.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            // 交付线程必须是 sweeper 本尊（枚举线程名，不断言调度器内部结构）
            assertEquals("production polling must run on serial-io-sweeper thread",
                    Collections.singleton("serial-io-sweeper"), deliveryThreads);

            // 第二个端口同挂生产调度器：仍只有一条 sweeper 线程（48 口共 1 根的线程账）
            SerialSourcePort portB = portWithMockSerialPort();
            when(portB.serialPort.bytesAvailable()).thenReturn(0);
            portB.startPolling();
            awaitTrue("second port poll task must tick", () -> portB.getPollTaskHandle() != null, 5000);
            int sweeperCount = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("serial-io-sweeper".equals(t.getName())) {
                    sweeperCount++;
                }
            }
            assertEquals("all ports share one serial-io-sweeper thread", 1, sweeperCount);
        } finally {
            SerialPollScheduler.bind(testScheduler);
        }
    }

    // ==================== 基建 ====================

    /**
     * 构造挂 mock SerialPort 的被测端口（mock isOpen=true 使 openPort 走 already-opened 早退，
     * 不碰真实设备）。真实口行为由 socat 真测用例覆盖，此处只测任务逻辑契约。
     */
    private SerialSourcePort portWithMockSerialPort() {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("MOCK-POLL-PORT", 9600, 8, 1, 0), 1, null);
        SerialPort mockPort = mock(SerialPort.class);
        when(mockPort.isOpen()).thenReturn(true);
        port.serialPort = mockPort;
        realPortUnderTest = port;
        realSourceUnderTest = new SerialSource(port, "mock-poll-test");
        return port;
    }

    /** jSerialComm 库内 waitForEvent 事件线程计数（旧事件模式每打开一口 +1）。 */
    private static int jsSerialCommEventThreadCount() {
        int count = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement element : entry.getValue()) {
                if ("com.fazecast.jSerialComm.SerialPort".equals(element.getClassName())
                        && "waitForEvent".equals(element.getMethodName())) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** awaitility 式有界条件自旋：验证「条件已成立」，超时即失败（非固定 sleep 猜测）。 */
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
