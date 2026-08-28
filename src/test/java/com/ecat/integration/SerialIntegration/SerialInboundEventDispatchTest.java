package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.Listener.BytePooledSerialDataListener;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 串口入站帧事件投递契约测试（arch-review 29 号 M3-P1 → W2-1 定稿形态）。
 *
 * <p>被测契约（{@link SerialEventDispatcher} 是 {@link SerialIoPool} per-port 串行视图的
 * 消费方——W2-1 键对齐裁定：finalize 必须与发帧写在同一条同口 FIFO 上，故归域池视图
 * 而非独立执行原语；「只投递不内联」的线程归属由 SerialIoPool 测试缝的捕获型替身表达）：
 * <ul>
 * <li>IO 线程只「读字节+组帧+投递」：监听器 onDataReceived 在 sweeper 线程命中完整帧后
 *     仅把事件体并入端口串行视图（O(1) 入队即返），不内联执行设备业务 finalize；</li>
 * <li>业务 finalize（responseFuture.complete + processResponse 续链）在端口视图的执行
 *     线程上执行（非投递线程），续链线程=事件体执行线程；</li>
 * <li>同口 FIFO：入站 finalize 与同口写任务按提交顺序串行（165500 承重顺序的投递面）；</li>
 * <li>域池拒绝（饱和/停机 REE）：dispatcher 记账不重试、不向监听器传播异常，future 留给
 *     SerialTimeoutScheduler 响应超时终态（「过期即弃」）；</li>
 * <li>无测试缝（真实域池）：事件体在 ecat-serial-io-N 线程执行。</li>
 * </ul>
 *
 * <p>同步纪律：全程 latch 确定性同步 + SerialIoPool 捕获缝手动驱动 drain，无 Thread.sleep。
 */
public class SerialInboundEventDispatchTest {

    private SerialIoPoolTest.ManualPoolSeam seam;

    @Before
    public void setUp() {
        SerialIoPool.resetForTest();
        seam = new SerialIoPoolTest.ManualPoolSeam();
        SerialIoPool.bindForTest(seam);
    }

    @After
    public void tearDown() {
        SerialIoPool.unbindForTest();
        SerialIoPool.resetForTest();
    }

    /**
     * 构造不经 jSerialComm 的 SerialSource 桩（真实端口对象不存在，getCommPort 会抛）：mock
     * SerialSource，submitInboundFrame 委托给真实 {@link SerialSourcePort}（本包内可见、
     * 构造不触发射口打开）——投递路径是被测真实实现，仅端口 IO 侧行为隔离。
     */
    private static SerialSource newSource(String portName) {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(portName, 9600, 8, 1, 0), 1, null);
        SerialSource source = org.mockito.Mockito.mock(SerialSource.class);
        org.mockito.Mockito.when(source.getPortName()).thenReturn(portName);
        org.mockito.Mockito.doAnswer(inv -> {
            port.submitInboundFrame(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(source).submitInboundFrame(org.mockito.ArgumentMatchers.<byte[]>any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(source).removeDataListener(org.mockito.ArgumentMatchers.any());
        return source;
    }

    /** 帧-check 恒命中（整段缓冲即完整帧）。 */
    private static final Function<byte[], byte[]> ALWAYS_FRAME = bytes -> bytes;

    private static ByteResponseHandlingContext<String> newContext() {
        return new ByteResponseHandlingContext<>("ctx-value");
    }

    // ===== 契约 1+2：IO 线程只投递；finalize 在端口视图执行线程执行 =====

    /**
     * 红测核心（03 §9-5，W2-1 形态）：监听器在「sweeper 线程」收到完整帧后只投递事件
     * （捕获缝收纳 drain、不驱动）；设备业务 finalize（processResponse 续链）必须跑在
     * 端口视图的执行线程上——以测试线程（模拟域池 worker）驱动捕获 drain 表达。
     * 改前行为：监听器内联 complete(responseFuture)，续链在投递线程 —— 断言失败。
     */
    @Test(timeout = 15000)
    public void processResponseRunsOnPortViewThreadNotOnSubmittingThread() throws Exception {
        SerialSource source = newSource("ttyUSBP1-worker");
        ByteResponseHandlingContext<String> context = newContext();
        CompletableFuture<ByteResponseHandlingContext<String>> responseFuture = new CompletableFuture<>();
        AtomicReference<String> continuationThread = new AtomicReference<>();
        CountDownLatch continuationDone = new CountDownLatch(1);
        responseFuture.thenApply(ctx -> {
            continuationThread.set(Thread.currentThread().getName());
            continuationDone.countDown();
            return ctx;
        });

        BytePooledSerialDataListener listener = new BytePooledSerialDataListener();
        listener.reset(context, responseFuture, source, ALWAYS_FRAME);

        // 在「sweeper 替身」线程上喂完整帧（模拟 SerialPortPollTask 读到字节后的通知路径）
        Thread sweeper = new Thread(() ->
                listener.onDataReceived(new byte[]{0x01, 0x02, 0x03}, 3), "serial-io-sweeper-standin");
        sweeper.start();
        sweeper.join(5000);

        assertTrue("drain 必须已提交到域池（捕获面）", seam.drainSubmissions.size() >= 1);
        // 模拟域池 worker：在「视图执行线程」上驱动 drain——续链必须在该线程，不在 sweeper
        Thread portViewWorker = new Thread(seam::runLastSubmission, "ecat-serial-io-0-standin");
        portViewWorker.start();
        portViewWorker.join(5000);

        assertTrue("responseFuture 必须在预算内被事件执行体完成", continuationDone.await(5, TimeUnit.SECONDS));
        assertEquals("设备业务 finalize 必须在端口视图执行线程上续链", "ecat-serial-io-0-standin",
                continuationThread.get());
        assertFalse("finalize 不得跑在 sweeper 投递线程", "serial-io-sweeper-standin".equals(continuationThread.get()));
    }

    /** 确定性验证「只投递」：捕获缝不驱动 → onDataReceived 返回后 future 必未完成。 */
    @Test(timeout = 15000)
    public void ioThreadOnlySubmitsNeverRunsFinalizeInline() throws Exception {
        SerialSource source = newSource("ttyUSBP1-submit-only");
        ByteResponseHandlingContext<String> context = newContext();
        CompletableFuture<ByteResponseHandlingContext<String>> responseFuture = new CompletableFuture<>();
        BytePooledSerialDataListener listener = new BytePooledSerialDataListener();
        listener.reset(context, responseFuture, source, ALWAYS_FRAME);

        listener.onDataReceived(new byte[]{0x0A}, 1);

        assertTrue("drain 必须已提交（投递已发生）", seam.drainSubmissions.size() >= 1);
        // 投递已发生但执行体未运行：sweeper 线程返回后 future 必然未完成（确定性——无人完成它）
        assertFalse("IO 线程不得内联执行 finalize（只投递）", responseFuture.isDone());

        // 手动驱动 drain → future 完成，且完成线程=执行线程（非投递线程）
        final AtomicReference<String> runner = new AtomicReference<>();
        Thread driver = new Thread(() -> {
            runner.set(Thread.currentThread().getName());
            seam.runLastSubmission();
        }, "ecat-serial-io-1-standin");
        driver.start();
        driver.join(5000);
        assertTrue(responseFuture.isDone());
        assertEquals("ecat-serial-io-1-standin", runner.get());
        assertFalse(runner.get().startsWith("serial-io-sweeper"));
    }

    // ===== 契约 3：同口 FIFO（finalize 与写同队有序，165500 承重顺序的投递面） =====

    /**
     * 入站 finalize 与同口写任务按提交顺序串行执行——证明 finalize 并入的是与写相同的
     * per-port FIFO 视图（W2-1 键对齐裁定：dispatcher 取 executorFor(connId)，与
     * SerialSourcePort.ioExecutor() 同一实例），而非独立执行队列。
     */
    @Test(timeout = 15000)
    public void inboundFinalizeCoOrderedWithWritesOnSamePortView() throws Exception {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("ttyUSBP1-order", 9600, 8, 1, 0), 1, null);
        java.util.List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch finalizeDone = new CountDownLatch(1);

        // 写任务先入队（模拟发帧读/写经 ioExecutor 同视图提交）
        SerialIoPool.executorFor("ttyUSBP1-order").execute(() -> order.add("write-before"));
        // 入站 finalize 投递（经真实 submitInboundFrame 路径）
        port.submitInboundFrame(new byte[]{0x0B}, () -> {
            order.add("inbound-finalize");
            finalizeDone.countDown();
        });
        // 写任务后入队
        SerialIoPool.executorFor("ttyUSBP1-order").execute(() -> order.add("write-after"));

        assertEquals("三类流量必须并入同一条同口 FIFO（单飞 drain）", 1, seam.drainSubmissions.size());
        seam.runLastSubmission();

        assertTrue("finalize 必须在 drain 排空内完成", finalizeDone.await(1, TimeUnit.MILLISECONDS)
                && finalizeDone.getCount() == 0);
        assertEquals(java.util.Arrays.asList("write-before", "inbound-finalize", "write-after"), order);
    }

    /** 异口隔离：另一口的写不排入本口 FIFO（各自独立队列/独立 drain）。 */
    @Test(timeout = 15000)
    public void distinctPortsHaveDistinctQueues() throws Exception {
        java.util.List<String> order = new CopyOnWriteArrayList<>();
        SerialIoPool.executorFor("ttyUSBA-iso").execute(() -> order.add("port-a"));
        SerialIoPool.executorFor("ttyUSBB-iso").execute(() -> order.add("port-b"));

        assertEquals("异口各起独立 drain", 2, seam.drainSubmissions.size());
        seam.runLastSubmission();   // 先排空 B 口（后提交者）
        assertEquals(java.util.Arrays.asList("port-b"), order);
        seam.drainSubmissions.get(0).run();   // 再排空 A 口
        assertEquals(java.util.Arrays.asList("port-b", "port-a"), order);
    }

    // ===== 契约 4：拒绝路径（不重试、不传播异常，靠响应超时兜底） =====

    @Test(timeout = 15000)
    public void poolRejectionIsAccountedNoRetryNoPropagation() throws Exception {
        SerialIoPool.unbindForTest();
        SerialIoPool.bindForTest((Executor) command -> {
            throw new RejectedExecutionException("serial IO pool saturated (test)");
        });
        SerialSource source = newSource("ttyUSBP1-drop");
        ByteResponseHandlingContext<String> context = newContext();
        CompletableFuture<ByteResponseHandlingContext<String>> responseFuture = new CompletableFuture<>();
        BytePooledSerialDataListener listener = new BytePooledSerialDataListener();
        listener.reset(context, responseFuture, source, ALWAYS_FRAME);

        // 域池拒绝被 dispatcher 善后（记账+告警），不向监听器传播（IO 线程不受污染）
        listener.onDataReceived(new byte[]{0x0D}, 1);
        // 拒绝后 Transport 不重试、不代执行：future 留给 SerialTimeoutScheduler 响应超时终态
        assertFalse("拒绝后不得有任何人完成 future（等待响应超时兜底）", responseFuture.isDone());
    }

    // ===== 契约 5：真实域池路径（无测试缝，惰性建池） =====

    @Test(timeout = 15000)
    public void withRealPoolBodyRunsOnDomainPoolThread() throws Exception {
        SerialIoPool.unbindForTest();
        SerialSource source = newSource("ttyUSBP1-realpool");
        ByteResponseHandlingContext<String> context = newContext();
        CompletableFuture<ByteResponseHandlingContext<String>> responseFuture = new CompletableFuture<>();
        AtomicReference<String> doneThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        responseFuture.thenApply(ctx -> {
            doneThread.set(Thread.currentThread().getName());
            done.countDown();
            return ctx;
        });

        BytePooledSerialDataListener listener = new BytePooledSerialDataListener();
        listener.reset(context, responseFuture, source, ALWAYS_FRAME);
        listener.onDataReceived(new byte[]{0x0E}, 1);

        assertTrue("事件体须在域池线程执行", done.await(5, TimeUnit.SECONDS));
        assertTrue("执行线程须为域池线程(ecat-serial-io-N): " + doneThread.get(),
                doneThread.get().matches("ecat-serial-io-\\d+"));
    }

    // ===== 载荷不可变（构造与读取双向防御性拷贝） =====

    /** SerialIoEvent 载荷不可变（frameBytes 防御性拷贝，构造与读取双向）。 */
    @Test
    public void serialIoEventIsImmutableDefensiveCopy() {
        byte[] frame = {1, 2, 3};
        SerialIoEvent event = new SerialIoEvent("serial-io:ttyUSBA", "ttyUSBA", frame, "io-serial-ttyUSBA-1");
        frame[0] = 99;
        assertEquals(1, event.getFrameBytes()[0]);
        event.getFrameBytes()[0] = 100;
        assertEquals(1, event.getFrameBytes()[0]);
        assertEquals("serial-io:ttyUSBA", event.getResourceKey());
        assertEquals("ttyUSBA", event.getConnId());
        assertEquals("io-serial-ttyUSBA-1", event.getRequestId());
    }
}
