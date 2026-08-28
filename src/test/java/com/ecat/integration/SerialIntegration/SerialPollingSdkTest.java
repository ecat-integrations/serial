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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;

/**
 * {@link SerialPolling} SDK 五维生命周期契约（17 号 v2.1 §2.1 + 16 号 §4.4）：
 * 周期（完成点重排）/ 断连状态转移行（首败 WARN/恢复 INFO 去重）/ 锁（busy-skip 内部消化）/
 * 超时（timeoutMs 覆盖）/ 异常韧性（round 抛异常不注销续排）+ cecep 链式双事务形态
 * （多段 thenCompose 一等公民，调研 07 §17）+ 111200 回归（轮询任务超时后下一轮正常
 * 执行，对照「sim bounce 后轮询永久死亡」僵尸形态）。
 *
 * <p>测试边界：真实 {@code SerialSourcePort} 锁状态机（不 openPort，零 jSerialComm
 * 副作用）+ mock SerialSource 桥接；定时经 {@code SerialSdkTimers.bindForTest} 注入
 * 测试自备真 STPE（毫秒精度 ≥ 原引擎 20ms tick，全部窗口断言不变）——与生产「域自持
 * 定时」链路同源（29 号 v2 S1：SdkSchedulerResolver 引擎路径退役）。宿主用 lambda
 * RemovalHost（宿主绑定契约由 startRegistersCancelAsRemovalAction_onHost 专项覆盖）。
 * 确定性同步 = CountDownLatch（负向断言用有界 await 返回值），无 Thread.sleep。
 *
 * <p>回调时序前提（构造性无竞态）：SDK 的 onRound 在事务 CF 的 handle 结算内同步执行，
 * 周期链以结算后的 CF 为重排依据——第 N 轮回调严格先于第 N+1 轮 round 体。
 *
 * @author coffee
 */
public class SerialPollingSdkTest {

    private static final long PERIOD_MS = 150L;
    /** 负向观察窗：> 2 个周期（含毫秒级取整余量），期间不得发生被排除的事件。 */
    private static final long NEGATIVE_WINDOW_MS = 400L;
    /** initialDelay 测试值：显著大于负向观察窗（窗内零发射才有区分力），显著小于 AWAIT_MS。 */
    private static final long INITIAL_DELAY_MS = 600L;
    /** initialDelay 负向观察窗：> 2 个周期（未生效时 ~1 tick 即发射）且 < INITIAL_DELAY_MS。 */
    private static final long DELAY_NEGATIVE_WINDOW_MS = 350L;
    private static final long AWAIT_MS = 5_000L;
    private static final long INTER_COMMAND_DELAY_MS = 200L;

    private java.util.concurrent.ScheduledExecutorService timers;
    private RemovalHost device;
    private SerialSourcePort port;
    private SerialSource source;
    private PollingHandle handle;

    @Before
    public void setUp() {
        // 域自持定时 + 测试缝 bind（29 号 v2 S1）：SDK 内部经 SerialSdkTimers 解析定时器，
        // 单测注入测试自备真 STPE（毫秒精度，窗口断言与原引擎形态同容差）
        SerialSdkTimers.resetForTest();
        timers = java.util.concurrent.Executors.newScheduledThreadPool(2,
                new NamedThreadFactory("serial-polling-sdk-test", true));
        SerialSdkTimers.bindForTest(SerialSdkTimers.forScheduledExecutor(timers));
        device = action -> { };
        port = newPort();
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

    // ==================== 周期：完成点重排（16 号 §4.4） ====================

    /**
     * 事务 CF 在飞期间不得按「发起段返回 + period」提前发射下一轮；事务完成后按
     * 完成点 + period 发射——SDK 经周期链（事务 CF 结算点重排）接入的直接验证。
     */
    @Test
    public void inFlightRoundDefersNextRoundToCompletionPoint() throws Exception {
        CompletableFuture<Boolean> firstRound = new CompletableFuture<>();
        CountDownLatch roundOneBegan = new CountDownLatch(1);
        CountDownLatch laterRound = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    if (rounds.incrementAndGet() == 1) {
                        roundOneBegan.countDown();
                        return firstRound;   // 首轮事务 CF 测试持有（在飞）
                    }
                    laterRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("首轮必须执行", roundOneBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("事务 CF 在飞期间不得提前发射下一轮",
                laterRound.await(NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));

        firstRound.complete(true);
        assertTrue("事务完成后下一轮必须发射（完成点 + period）",
                laterRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * fixedRate()：名义网格语义——在飞轮占住的拍跳过（不堆叠不重入），事务完成后的
     * 下一个网格拍（网格点量级，&lt; 1 个周期）即发射下一轮，而非 fixedDelay 的
     * 完成点 + period。区分窗：完成后 100ms 内必须发射（fixedDelay 语义下须 ≥150ms）。
     */
    @Test
    public void fixedRateFiresNextRoundOnGridTickAfterCompletion() throws Exception {
        CompletableFuture<Boolean> firstRound = new CompletableFuture<>();
        CountDownLatch roundOneBegan = new CountDownLatch(1);
        CountDownLatch laterRound = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    if (rounds.incrementAndGet() == 1) {
                        roundOneBegan.countDown();
                        return firstRound;   // 首轮事务 CF 测试持有（跨多个拍在飞）
                    }
                    laterRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .fixedRate()
                .start();

        assertTrue("首轮必须执行", roundOneBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("在飞轮占住的拍必须跳过（不堆叠重入）",
                laterRound.await(NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));

        firstRound.complete(true);
        assertTrue("fixedRate 完成后下一拍即发射（网格拍 < 1 周期；fixedDelay 语义须 ≥ 1 周期）",
                laterRound.await(100L, TimeUnit.MILLISECONDS));
    }

    // ==================== 断连状态转移行：首败 WARN / 恢复 INFO / 去重 ====================

    /**
     * 测试缝：摘下 logback 全局 TurboFilter（ecat-core logback.xml 的 ErrorRateLimitFilter
     * 对 WARN/ERROR 做 3s 窗口限频去重——生产日志预算策略，有独立测试）。本节断言的是
     * SDK 自身的转移行发射语义（首败/恢复各恰一行），须在无限频的通道上观察，用毕恢复。
     */
    private java.util.List<ch.qos.logback.classic.turbo.TurboFilter> detachTurboFilters() {
        ch.qos.logback.classic.LoggerContext ctx =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        java.util.List<ch.qos.logback.classic.turbo.TurboFilter> saved =
                new java.util.ArrayList<>(ctx.getTurboFilterList());
        ctx.getTurboFilterList().clear();
        return saved;
    }

    private void restoreTurboFilters(
            java.util.List<ch.qos.logback.classic.turbo.TurboFilter> saved) {
        ch.qos.logback.classic.LoggerContext ctx =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ctx.getTurboFilterList().addAll(saved);
    }

    /**
     * comm 熔断退役（W3）的补偿观测：连续失败轮只在<b>首败</b>打一行 WARN「link DOWN」、
     * 断连后的<b>首个成功轮</b>打一行 INFO「link RECOVERED」——per-round ERROR 全栈照打
     * （可 grep 定位根因），转移行给运维一眼可见的连续断连/恢复时间线。
     */
    @Test
    public void consecutiveFailuresLogOneDownLine_thenOneRecoveryLine() throws Exception {
        ch.qos.logback.classic.Logger pollingLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SerialPolling.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Level originalLevel = pollingLogger.getLevel();
        pollingLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        pollingLogger.addAppender(appender);
        java.util.List<ch.qos.logback.classic.turbo.TurboFilter> turboFilters = detachTurboFilters();
        try {
            AtomicBoolean failing = new AtomicBoolean(true);
            CountDownLatch threeFailures = new CountDownLatch(3);
            CountDownLatch successRound = new CountDownLatch(1);

            handle = SerialPolling.on(device, source)
                    .round(src -> {
                        if (failing.get()) {
                            CompletableFuture<Boolean> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new java.io.IOException("sim link down"));
                            return failed;
                        }
                        return CompletableFuture.completedFuture(true);
                    })
                    .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                    .onRound((result, ex) -> {
                        if (ex != null) {
                            threeFailures.countDown();
                        } else if (Boolean.TRUE.equals(result)) {
                            successRound.countDown();
                        }
                    })
                    .start();

            assertTrue("前置：3 个失败轮必须发生", threeFailures.await(AWAIT_MS, TimeUnit.MILLISECONDS));
            failing.set(false);
            assertTrue("恢复轮必须发生", successRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));

            long downLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("link DOWN"))
                    .count();
            long recoveredLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                    .filter(e -> e.getFormattedMessage().contains("link RECOVERED"))
                    .count();
            long errorLines = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .count();
            assertEquals("连续失败期 DOWN 行恰一条（去重，首败一次）", 1L, downLines);
            assertEquals("恢复行恰一条（首个成功轮）", 1L, recoveredLines);
            assertTrue("per-round ERROR 全栈照打（每失败轮一条，实得 " + errorLines + "）",
                    errorLines >= 3L);
        } finally {
            restoreTurboFilters(turboFilters);
            pollingLogger.detachAppender(appender);
            pollingLogger.setLevel(originalLevel);
        }
    }

    /** 业务失败轮（Boolean.FALSE，如 PM3000E 版本失配整轮不可用）同样进入断连态；TRUE 轮恢复。 */
    @Test
    public void businessFalseRoundEntersDownState_andTrueRoundRecovers() throws Exception {
        ch.qos.logback.classic.Logger pollingLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SerialPolling.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        pollingLogger.addAppender(appender);
        java.util.List<ch.qos.logback.classic.turbo.TurboFilter> turboFilters = detachTurboFilters();
        try {
            CountDownLatch falseRound = new CountDownLatch(1);
            CountDownLatch trueRound = new CountDownLatch(1);

            handle = SerialPolling.on(device, source)
                    .round(src -> falseRound.getCount() == 0
                            ? CompletableFuture.completedFuture(true)
                            : CompletableFuture.completedFuture(false))
                    .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                    .onRound((result, ex) -> {
                        if (Boolean.FALSE.equals(result)) {
                            falseRound.countDown();
                        } else if (Boolean.TRUE.equals(result)) {
                            trueRound.countDown();
                        }
                    })
                    .start();

            assertTrue(falseRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(trueRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));

            assertTrue("FALSE 轮后必须出现 DOWN 行",
                    appender.list.stream().anyMatch(e ->
                            e.getLevel() == ch.qos.logback.classic.Level.WARN
                                    && e.getFormattedMessage().contains("link DOWN")));
            assertTrue("TRUE 轮后必须出现 RECOVERED 行",
                    appender.list.stream().anyMatch(e ->
                            e.getLevel() == ch.qos.logback.classic.Level.INFO
                                    && e.getFormattedMessage().contains("link RECOVERED")));
        } finally {
            restoreTurboFilters(turboFilters);
            pollingLogger.detachAppender(appender);
        }
    }

    // ==================== 锁：busy-skip 内部消化（LockBusySkippedException 不外泄） ====================

    /**
     * 端口锁被外部持有（写命令事务在飞的真实形态）：tryAcquire 锁忙轮被 SDK 内部消化——
     * onRound 不回调、无错误外泄，调度网格照常推进；锁释放后下一轮即恢复采集。
     */
    @Test
    public void lockBusyRoundIsDigestedWithoutCallbackOrDeregistration() throws Exception {
        String heldKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("前置：测试线程持锁", heldKey);

        CountDownLatch roundRan = new CountDownLatch(1);
        CountDownLatch successCallback = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    roundRan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    callbacks.incrementAndGet();
                    if (ex == null && Boolean.TRUE.equals(result)) {
                        successCallback.countDown();
                    }
                })
                .start();

        assertFalse("锁忙轮 round 不得执行（tryAcquire 即弃）",
                roundRan.await(NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));
        assertEquals("锁忙轮 onRound 不得回调（LockBusySkippedException 内部消化，不外泄）",
                0, callbacks.get());
        assertTrue("锁忙轮后轮询不得注销（跳过轮网格推进）", handle.isRunning());

        assertTrue("释放锁失败", port.release(heldKey));
        assertTrue("锁释放后下一轮必须恢复采集", roundRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("恢复轮须回调 (true, null)", successCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
    }

    // ==================== 超时：默认派生事务硬超时（SDK 级覆盖词汇零消费已删） ====================

    /**
     * round 返回永不完成的 CF：默认派生事务硬超时（source 串口读超时 × 10）到点异常完成、
     * onRound(null, TimeoutException)。长事务设备按设备配置的串口读超时声明（派生自 source）。
     */
    @Test
    public void derivedTransactionTimeoutSurfacesToCallback() throws Exception {
        CountDownLatch failureCallback = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        // 收窄派生窗（30ms 读超时 × 10 = 300ms，显著小于 AWAIT_MS）：本测被测者是「默认派生值生效并上浮回调」
        when(source.getTimeout()).thenReturn(30);

        handle = SerialPolling.on(device, source)
                .round(src -> new CompletableFuture<>())   // 永不完成：挂死事务形态
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex != null) {
                        seen.set(ex);
                        failureCallback.countDown();
                    }
                })
                .start();

        assertTrue("派生事务硬超时必须到点触发（30ms×10=300ms，非本派生值不触发）",
                failureCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        Throwable root = seen.get();
        while (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue("失败原因必须是 TimeoutException（事务级硬超时），实际: " + root,
                root instanceof TimeoutException);
    }

    // ==================== 异常韧性：round 抛异常不注销续排 ====================

    /** round 体同步抛异常：onRound(null, ex) 回调一次，轮询不注销，下一轮照常执行。 */
    @Test
    public void roundThrowingSynchronouslyDoesNotDeregister() throws Exception {
        CountDownLatch errorCallback = new CountDownLatch(1);
        CountDownLatch secondRound = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    if (rounds.incrementAndGet() == 1) {
                        throw new IllegalStateException("round boom");
                    }
                    secondRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex != null) {
                        errorCallback.countDown();
                    }
                })
                .start();

        assertTrue("同步异常必须经 onRound(null, ex) 外显", errorCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("异常轮后下一轮必须照常执行（永不注销）",
                secondRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("异常后句柄仍在调度", handle.isRunning());
    }

    // ==================== 结果语义：false 警告 / null 成功（thermofisher CF<Void> 形态） ====================

    /** Boolean.FALSE = 业务失败（正常完成、不进异常通道）；null（CF<Void>）= 成功。 */
    @Test
    public void falseResultIsBusinessFailureAndNullResultIsSuccess() throws Exception {
        CountDownLatch twoCallbacks = new CountDownLatch(2);
        AtomicReference<Boolean> firstResult = new AtomicReference<>();
        AtomicReference<Boolean> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> anyError = new AtomicReference<>();
        AtomicInteger rounds = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    boolean first = rounds.incrementAndGet() == 1;
                    return CompletableFuture.completedFuture(first ? Boolean.FALSE : null);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex != null) {
                        anyError.set(ex);
                    }
                    if (rounds.get() == 1) {
                        firstResult.set(result);
                    } else {
                        secondResult.set(result);
                    }
                    twoCallbacks.countDown();
                })
                .start();

        assertTrue("两轮必须完成回调", twoCallbacks.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertNull("false/null 均非错误，不得进异常通道", anyError.get());
        assertEquals("第一轮 FALSE 须以业务失败语义回调（result=false）",
                Boolean.FALSE, firstResult.get());
        assertNull("CF<Void> 的 null 结果须按成功回调（result=null）", secondResult.get());
    }

    // ==================== cecep 链式双事务：多段 thenCompose 一等公民 ====================

    /**
     * cecep TRAMC500 形态（调研 07 §17：同周期两块读经 thenRun 串行避免 tryAcquire 互踩）：
     * SDK 把多段链合并为单 round 单事务——段间经 {@code delay()}（interCommandDelayMs，
     * 收编本地 delay() 样板）留隙，单 Boolean 出口。两步构建：round 体无竞态引用 polling。
     */
    @Test
    public void cecepStyleMultiSegmentChainWithInterCommandDelayIsFirstClass() throws Exception {
        final SerialPolling polling = SerialPolling.on(device, source)
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .interCommandDelayMs(INTER_COMMAND_DELAY_MS);

        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch segmentOne = new CountDownLatch(1);
        CountDownLatch segmentTwo = new CountDownLatch(1);
        final long[] segmentOneEndNanos = new long[1];
        final long[] segmentTwoStartNanos = new long[1];
        AtomicReference<Boolean> roundResult = new AtomicReference<>();
        AtomicReference<Throwable> roundError = new AtomicReference<>();

        handle = polling
                .round(src -> CompletableFuture.<Boolean>completedFuture(true)
                        .thenApply(v -> {
                            segmentOneEndNanos[0] = System.nanoTime();
                            segmentOne.countDown();
                            return v;
                        })
                        .thenCompose(v -> polling.delay())
                        .thenCompose(v -> {
                            segmentTwoStartNanos[0] = System.nanoTime();
                            segmentTwo.countDown();
                            return CompletableFuture.completedFuture(true);
                        }))
                .onRound((result, ex) -> {
                    roundResult.set(result);
                    roundError.set(ex);
                    done.countDown();
                })
                .start();

        assertTrue("链式 round 必须完成", done.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertNull("链式 round 不得异常: " + roundError.get(), roundError.get());
        assertEquals("单 Boolean 出口（多段链合一 round）", Boolean.TRUE, roundResult.get());
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(
                segmentTwoStartNanos[0] - segmentOneEndNanos[0]);
        assertTrue("两段之间必须实际经过 interCommandDelay（实测 " + gapMillis + "ms ≥ "
                        + (INTER_COMMAND_DELAY_MS - 50) + "ms）",
                gapMillis >= INTER_COMMAND_DELAY_MS - 50);
    }

    // ==================== 111200 回归：轮询任务超时后下一轮正常执行 ====================

    /**
     * bug-record-20260826-111200（sim 短暂停机→重启后轮询永久死亡，违反「永不注销」）：
     * 超时轮（挂死事务经事务级硬超时异常完成）之后，轮询必须继续推进——连续多轮正常采集，
     * 对照「超时后零活动」的僵尸形态。SDK 的统一异常结算 + 周期链永不注销是其修复载体。
     */
    @Test
    public void bug111200_pollingSurvivesTimeoutRoundAndContinues() throws Exception {
        CountDownLatch laterRounds = new CountDownLatch(3);
        CountDownLatch timeoutCallback = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();
        // 收窄派生超时窗（30ms × 10 = 300ms）：挂死轮按默认派生硬超时定案，测试墙钟可控
        when(source.getTimeout()).thenReturn(30);

        handle = SerialPolling.on(device, source)
                .round(src -> rounds.incrementAndGet() == 1
                        ? new CompletableFuture<Boolean>()   // 第 1 轮挂死：超时强拆形态
                        : CompletableFuture.completedFuture(true))
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .onRound((result, ex) -> {
                    if (ex != null) {
                        timeoutCallback.countDown();
                    } else if (rounds.get() > 1) {
                        laterRounds.countDown();
                    }
                })
                .start();

        assertTrue("挂死轮必须被事务硬超时异常完成", timeoutCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("超时轮之后必须连续推进（3 轮正常采集，僵尸形态为 0 轮）",
                laterRounds.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("超时后句柄仍在调度", handle.isRunning());
    }

    // ==================== 句柄：cancel 语义（对齐 readFuture.cancel） ====================

    /** cancel 后不再排新轮、isRunning=false；幂等。 */
    @Test
    public void cancelStopsSchedulingAndIsIdempotent() throws Exception {
        CountDownLatch firstRound = new CountDownLatch(1);
        CountDownLatch fourthRound = new CountDownLatch(4);
        AtomicInteger rounds = new AtomicInteger();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    rounds.incrementAndGet();
                    firstRound.countDown();
                    fourthRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("首轮必须执行", firstRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        handle.cancel();
        handle.cancel();   // 幂等
        assertFalse("cancel 后 isRunning 必须为 false", handle.isRunning());

        int afterCancel = rounds.get();
        assertFalse("cancel 后不得再执行新轮（容忍 cancel 前在途一轮）",
                fourthRound.await(500L, TimeUnit.MILLISECONDS));
        assertTrue("轮次计数不得持续增长", rounds.get() <= afterCancel + 1);
    }

    // ==================== 首轮延迟：initialDelay（R5b，默认 0 = 立即首轮） ====================

    /**
     * initialDelay 推迟首轮：延迟窗内 round 不得执行（负向有界窗，未生效时 ~1 毫秒级
     * 即发射必使断言变红），到点后首轮必须发射且句柄处于在调度态。
     *
     * <p>迁移动机（R5b）：santak 5s / teledyne-api 1s / tjtongyangkeji 1s+2s 仓
     * 「schedule 一次性任务 → 任务体内 start()」workaround 的 SDK 原生承载——首轮延迟
     * 进周期链首发延迟（任务在 start() 即注册宿主移除，延迟窗内 stop 的托管 sweep
     * 与 cancel 同样生效），取代「先排一次性任务、到点再注册轮询」的两步形态。
     */
    @Test
    public void initialDelayDefersFirstRoundUntilDelayElapses() throws Exception {
        CountDownLatch firstRound = new CountDownLatch(1);

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    firstRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .initialDelay(INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .start();

        assertFalse("initialDelay 窗内首轮不得执行（未生效则 ~1 tick 内发射）",
                firstRound.await(DELAY_NEGATIVE_WINDOW_MS, TimeUnit.MILLISECONDS));
        assertTrue("initialDelay 到点后首轮必须发射",
                firstRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("延迟首轮后轮询须在调度态", handle.isRunning());
    }

    /** 默认（不配置 initialDelay）首轮立即发射：与全部存量设备仓行为一致的回归锁。 */
    @Test
    public void defaultInitialDelayFiresFirstRoundImmediately() throws Exception {
        CountDownLatch firstRound = new CountDownLatch(1);

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    firstRound.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("默认首轮必须立即发射（initialDelay=0 回归锁，窗 200ms << 周期 150ms 的错拍形态）",
                firstRound.await(200L, TimeUnit.MILLISECONDS));
    }

    /** initialDelay 构建契约（严格模式）：负值 / null unit 显式拒绝，非静默纠正。 */
    @Test
    public void initialDelayRejectsNegativeAndNullUnit() {
        SerialPolling polling = SerialPolling.on(device, source);
        try {
            polling.initialDelay(-1L, TimeUnit.MILLISECONDS);
            fail("负 initialDelay 必须显式拒绝");
        } catch (IllegalArgumentException expected) { /* 契约 */ }

        try {
            polling.initialDelay(1L, null);
            fail("null unit 必须显式拒绝");
        } catch (IllegalArgumentException expected) { /* 契约 */ }
    }

    // ==================== 构建契约（严格模式） ====================

    /** delay() 未配置 interCommandDelayMs 是编程错误：显式拒绝（非静默零延迟）。 */
    @Test
    public void delayWithoutInterCommandDelayConfigIsRejected() {
        SerialPolling polling = SerialPolling.on(device, source);
        try {
            polling.delay();
            fail("未配置 interCommandDelayMs 时 delay() 必须显式拒绝");
        } catch (IllegalStateException expected) {
            // 契约：显式失败而非静默零延迟
        }
    }

    /** 缺 round / 缺 every / 重复 start（同实例）均为构建契约违背：显式拒绝。 */
    @Test
    public void startValidatesRoundPeriodAndSingleStart() throws Exception {
        try {
            SerialPolling.on(device, source).every(PERIOD_MS, TimeUnit.MILLISECONDS).start();
            fail("缺 round 必须 start 失败");
        } catch (IllegalStateException expected) { /* 契约 */ }

        try {
            SerialPolling.on(device, source)
                    .round(src -> CompletableFuture.completedFuture(true))
                    .start();
            fail("缺 every 必须 start 失败");
        } catch (IllegalStateException expected) { /* 契约 */ }

        SerialPolling builder = SerialPolling.on(device, source)
                .round(src -> CompletableFuture.completedFuture(true))
                .every(PERIOD_MS, TimeUnit.MILLISECONDS);
        handle = builder.start();
        try {
            builder.start();   // 同实例二次 start
            fail("同实例二次 start 必须拒绝");
        } catch (IllegalStateException expected) { /* 契约 */ }
    }

    // ==================== 生命周期内绑（18 号 §3.3） ====================

    /**
     * start() 必须把 {@code handle::cancel} 注册为宿主移除动作（SDK 内绑，L3 作者不可能漏）；
     * 收集断言型假宿主观察注册，动作执行后轮询停（isRunning=句柄 cancelled 的确定判读，无等待）。
     */
    @Test
    public void startRegistersCancelAsRemovalAction_onHost() {
        java.util.List<Runnable> removals = new java.util.ArrayList<>();
        PollingHandle started = SerialPolling.on(removals::add, source)
                .round(src -> CompletableFuture.completedFuture(true))
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();
        assertTrue("start() 后轮询在跑（未 cancel）", started.isRunning());
        assertEquals("start() 必须恰注册一个移除动作（handle::cancel）", 1, removals.size());

        removals.get(0).run();   // 宿主 sweep 执行移除动作
        assertFalse("移除动作执行后轮询必须停（cancel 生效）", started.isRunning());
    }

    /** host 必填（无双 API）：null 宿主显式拒绝。 */
    @Test
    public void onNullHostRejected() {
        try {
            SerialPolling.on(null, source);
            fail("on(null, source) 必须显式拒绝");
        } catch (IllegalArgumentException expected) {
            // 契约：host 必填——忘了绑定在签名层面不可能
        }
    }

    // ==================== 装配 ====================

    /** 真实锁状态机端口（不 openPort，构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("SDK-TEST-PORT", 9600, 8, 1, 0), 1, null);
    }

    /** 桥接到真实端口的 SerialSource mock：executePolling 的锁状态机/释放穿过真实实现。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.tryAcquire()).thenAnswer(inv -> port.tryAcquire());
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        when(source.getPortName()).thenReturn(port.getPortName());
        when(source.getTimeout()).thenReturn(port.getTimeout());
        return source;
    }

}
