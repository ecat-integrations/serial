package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;

/**
 * {@link SerialPolling#roundChain()} 多段轮契约（与 SerialPollingSdkTest 同构的测试边界：
 * 真实 {@code SerialSourcePort} 锁状态机 + mock SerialSource 桥接 + 测试自备真 STPE 定时缝）：
 * <ul>
 *   <li>修复语义：段间留隙在源锁临界区之外——写命令可在留隙窗内取锁（对照面是单段
 *       round() 体内 delay 留隙：单事务整轮持锁）；</li>
 *   <li>段契约：每段独立 executePolling 事务（独立预算取锁/独立硬超时/完成即释放）；
 *       段体显式 FALSE 或异常 ⇒ 中止不追读后续段；</li>
 *   <li>结算兼容：一轮恰好一次 onRound 回调（末段结局）；锁忙弃轮照旧内部消化不回调。</li>
 * </ul>
 * 确定性同步 = CountDownLatch + CF 完成线程同步落定性质（结算含释放先于 complete() 返回），
 * 无 Thread.sleep。
 *
 * @author coffee
 */
public class SerialPollingRoundChainTest {

    private static final long PERIOD_MS = 150L;
    /** 负向观察窗：覆盖一个 gap 到点（含余量），期间不得发生被排除的事件。 */
    private static final long NEGATIVE_WINDOW_MS = 300L;
    private static final long AWAIT_MS = 5_000L;

    private ScheduledExecutorService timers;
    private RemovalHost device;
    private SerialSourcePort port;
    private SerialSource source;
    private PollingHandle handle;

    @Before
    public void setUp() {
        SerialSdkTimers.resetForTest();
        timers = Executors.newScheduledThreadPool(2,
                new NamedThreadFactory("serial-round-chain-test", true));
        SerialSdkTimers.bindForTest(SerialSdkTimers.forScheduledExecutor(timers));
        device = action -> { };
        port = new SerialSourcePort(new SerialInfo("SDK-TEST-PORT", 9600, 8, 1, 0), 1, null);
        source = bridgedSource(port);
    }

    @After
    public void tearDown() {
        if (handle != null) {
            handle.cancel();
        }
        SerialSdkTimers.unbindForTest();
        timers.shutdownNow();
        SerialIoPool.resetForTest();
    }

    // ==================== 修复主断言：段间留隙在锁外 ====================

    /**
     * 修复语义核心：两段轮的段间留隙期间源锁空闲——写者立即取走；写者释放后段二重新
     * 取锁执行，整轮单回调 (true, null) 收尾。段一结算权由测试持有（complete 在测试线程
     * 落定：结算含释放同步先于 complete() 返回），留隙窗断言因此无竞态。
     */
    @Test
    public void gapBetweenSegmentsIsLockFree_writerAcquiresAndSecondSegmentContinues() throws Exception {
        CompletableFuture<Boolean> seg1Gate = new CompletableFuture<>();
        CountDownLatch seg1Began = new CountDownLatch(1);
        CountDownLatch seg2Ran = new CountDownLatch(1);
        CountDownLatch roundSettled = new CountDownLatch(1);

        handle = SerialPolling.on(device, source)
                .roundChain()
                .held(src -> {
                    seg1Began.countDown();
                    return seg1Gate;
                })
                .gap(400L)
                .held(src -> {
                    seg2Ran.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .end()
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex == null && Boolean.TRUE.equals(result)) {
                        roundSettled.countDown();
                    }
                })
                .start();

        assertTrue("前置：段一体必须已执行（锁持有中）", seg1Began.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        seg1Gate.complete(true); // 段一结算+释放在本线程同步落定，gap 单发登记

        String writerKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("段间留隙期间源锁必须空闲（写者可立即取锁）", writerKey);

        assertTrue(port.release(writerKey));
        assertTrue("段二必须在写者释放后获锁执行（同轮继续）",
                seg2Ran.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("整轮以 (true, null) 单回调收尾", roundSettled.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        handle.cancel();
    }

    /**
     * 对照基线（默认路径契约存档，实现后仍须绿）：单段 round() 体内经 delay(ms) 留隙是
     * 同一个源锁事务——留隙期间锁被持有，写者取不到；轮结算释放后写者恢复可取。
     */
    @Test
    public void inRoundDelayUnderSingleRoundHoldsLock_singleTransactionContract() throws Exception {
        SerialPolling polling = SerialPolling.on(device, source);
        CountDownLatch roundBegan = new CountDownLatch(1);
        handle = polling
                .round(src -> {
                    roundBegan.countDown();
                    return CompletableFuture.completedFuture(true)
                            .thenCompose(v -> polling.delay(500L, TimeUnit.MILLISECONDS))
                            .thenCompose(v -> CompletableFuture.completedFuture(true));
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("前置：round 体必须已执行（锁持有中）", roundBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        String writerKey = port.acquire(300L, TimeUnit.MILLISECONDS);
        assertNull("单事务 round 的体内留隙在锁内（对照契约：写者此窗不可得锁）", writerKey);

        String writerKeyAfter = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("轮结算释放后写者恢复可取锁", writerKeyAfter);
        assertTrue(port.release(writerKeyAfter));
        handle.cancel();
    }

    // ==================== 折叠规则：中段失败不追读 ====================

    /** 中段显式 FALSE：不追读后续段（后续 gap 不再登记），整轮以 (false, null) 结算回调。 */
    @Test
    public void midSegmentFalseSkipsRemainingSegments() throws Exception {
        CountDownLatch seg3Ran = new CountDownLatch(1);
        CountDownLatch falseSettled = new CountDownLatch(1);

        handle = SerialPolling.on(device, source)
                .roundChain()
                .held(src -> CompletableFuture.completedFuture(true))
                .gap(200L)
                .held(src -> CompletableFuture.completedFuture(false)) // 中段业务失败
                .gap(200L)
                .held(src -> {
                    seg3Ran.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .end()
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (Boolean.FALSE.equals(result)) {
                        falseSettled.countDown();
                    }
                })
                .start();

        assertTrue("中段 false 须整轮以 (false, null) 结算回调", falseSettled.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("中段失败后不得追读后续段（负向观察窗覆盖 gap2 到点）",
                seg3Ran.await(NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));
        assertTrue(handle.isRunning());
        handle.cancel();
    }

    /** 三段全 true：三段体全部执行，整轮恰好一次 (true, null) 回调（末段结局=轮结局）。 */
    @Test
    public void allSegmentsTrueSettlesOnceWithTrue() throws Exception {
        CountDownLatch allThreeRan = new CountDownLatch(3);
        CountDownLatch roundSettled = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .roundChain()
                .held(src -> {
                    allThreeRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .gap(100L)
                .held(src -> {
                    allThreeRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .gap(100L)
                .held(src -> {
                    allThreeRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .end()
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    callbacks.incrementAndGet();
                    if (ex == null && Boolean.TRUE.equals(result)) {
                        roundSettled.countDown();
                    }
                })
                .start();

        assertTrue("三段体必须全部执行", allThreeRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("整轮以 (true, null) 结算", roundSettled.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        handle.cancel(); // 先停后续轮，回调计数才可断言
        assertEquals("一轮恰好一次 onRound 回调（多段不放大回调数）", 1, callbacks.get());
    }

    // ==================== 锁忙弃轮：内部消化不回调（chain 形态同契约） ====================

    /** 端口锁被外部持有：chain 首段预算耗尽真弃轮——段体不执行、不回调、网格照常推进。 */
    @Test
    public void lockBusyFirstSegmentIsDigestedWithoutCallback() throws Exception {
        String heldKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("前置：测试线程持锁", heldKey);

        CountDownLatch bodyRan = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .roundChain()
                .held(src -> {
                    bodyRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .gap(100L)
                .held(src -> {
                    bodyRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .end()
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> callbacks.incrementAndGet())
                .start();

        awaitLockBusySkipCount(1);
        assertEquals("锁忙轮段体不得执行", 1, bodyRan.getCount());
        assertEquals("锁忙轮不回调（LockBusySkippedException 内部消化）", 0, callbacks.get());
        assertTrue("锁忙弃轮后轮询不得注销", handle.isRunning());

        assertTrue(port.release(heldKey));
        assertTrue("锁释放后下一轮必须恢复采集", bodyRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        handle.cancel();
    }

    /** 真弃轮记账事件等待（deadline 轮询：记账发生在旁池线程，立即读数是竞态）。 */
    private void awaitLockBusySkipCount(long expected) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (port.getLockBusySkipCount() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                fail("弃轮记账未在 " + AWAIT_MS + "ms 内发生: expected>=" + expected
                        + ", actual=" + port.getLockBusySkipCount());
            }
        }
    }

    // ==================== 按段独立硬超时 ====================

    /** 段一挂死：事务硬超时（读超时×10=300ms）异常回调 TimeoutException、锁归还、不追读段二。 */
    @Test
    public void segmentHardTimeoutAbortsChainAndSurfacesTimeout() throws Exception {
        when(source.getTimeout()).thenReturn(30); // 派生事务硬超时 = 30×10 = 300ms
        CountDownLatch seg2Ran = new CountDownLatch(1);
        CountDownLatch timeoutCallback = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();

        handle = SerialPolling.on(device, source)
                .roundChain()
                .held(src -> new CompletableFuture<Boolean>()) // 段一挂死
                .gap(100L)
                .held(src -> {
                    seg2Ran.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .end()
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex != null) {
                        seen.set(ex);
                        timeoutCallback.countDown();
                    }
                })
                .start();

        assertTrue("挂死段须按事务硬超时异常回调", timeoutCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        Throwable root = seen.get();
        while (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue("超时根因应为 TimeoutException，实际: " + root,
                root instanceof TimeoutException);
        assertFalse("超时中止折叠：后续段不得追读（负向观察窗覆盖 gap 到点）",
                seg2Ran.await(NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));
        String writerKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("硬超时段的锁必须已归还（幽灵锁零容忍）", writerKey);
        assertTrue(port.release(writerKey));
        handle.cancel();
    }

    // ==================== 构建契约（严格模式 fail-fast） ====================

    /** roundChain 声明序与互斥规则：非法序列一律 fail-fast，不带病进入 start()。 */
    @Test
    public void roundChainBuilderContractFailFast() {
        Function<SerialSource, CompletableFuture<Boolean>> ok =
                src -> CompletableFuture.completedFuture(Boolean.TRUE);

        try {
            SerialPolling.on(device, source).roundChain().end();
            fail("end() 前无任何 held 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        try {
            SerialPolling.on(device, source).roundChain().gap(100L);
            fail("gap 必须跟在 held 之后");
        } catch (IllegalStateException expected) { }

        try {
            SerialPolling.on(device, source).roundChain().held(ok).gap(1L).gap(1L);
            fail("重复 gap 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        try {
            SerialPolling.on(device, source).roundChain().held(ok).gap(1L).held(ok).gap(1L).end();
            fail("尾随 gap 无所属段必须 fail-fast");
        } catch (IllegalStateException expected) { }

        try {
            SerialPolling.on(device, source).roundChain().held(ok).gap(0L);
            fail("gap(0) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            SerialPolling.on(device, source).roundChain().held(ok).gap(-1L);
            fail("gap(负) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }

        try {
            SerialPolling.on(device, source).roundChain().held(null);
            fail("held(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }

        try {
            SerialPolling.on(device, source).round(ok).roundChain();
            fail("round() 已声明后再 roundChain() 必须 fail-fast");
        } catch (IllegalStateException expected) { }
        SerialPolling chainPolling = SerialPolling.on(device, source);
        chainPolling.roundChain().held(ok).end();
        try {
            chainPolling.round(ok);
            fail("roundChain() 已声明后再 round() 必须 fail-fast");
        } catch (IllegalStateException expected) { }

        try {
            SerialPolling.on(device, source).every(1, TimeUnit.SECONDS).start();
            fail("start() 前必须声明 round 或 roundChain");
        } catch (IllegalStateException expected) { }
    }

    // ==================== 装配 ====================

    /** 桥接到真实端口的 SerialSource mock：executePolling 的锁状态机/释放穿过真实实现。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.acquirePollingBounded(anyLong())).thenAnswer(inv -> port.acquirePollingBounded(
                null, inv.getArgument(0, Long.class)));
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        when(source.getPortName()).thenReturn(port.getPortName());
        when(source.getTimeout()).thenReturn(port.getTimeout());
        return source;
    }
}
