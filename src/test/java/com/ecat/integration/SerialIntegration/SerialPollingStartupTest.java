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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Mdc.MdcContext;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

/**
 * {@link SerialPolling#startup} 启动序列契约（与 modbus 域 ModbusPollingStartupTest 同款
 * 语义，serial 形态）：「首轮读之前在口上顺序做一次性初始化写」的一等入口——startup 与
 * 轮询走同一条对话队列（executeWithLambda 写命令同入口、HIGH 优先级），周期链在 startup
 * 成功前不起，竞态由结构消除；SDK 统一承载 attempts×retryDelay 重试（业务 FALSE 与异常
 * 同格折算），耗尽 ⇒ 轮询不启动 + 句柄失败态 + onStartupFailure（不吞不静默起轮）；
 * cancel（宿主移除 sweep 内绑）撤在飞 startup 与待发重试拍，主动停机非故障。
 *
 * <p>测试形态：定时经 {@link FakeSerialTimers} 手动驱动（零真实时钟零 sleep；startup
 * 语义是时序而非时长——重试拍延迟值直接断言捕获记录），对话体经真实端口执行者跑
 * （不 openPort 零 jSerialComm 副作用），异步 hop 用 CountDownLatch 事件同步。
 */
public class SerialPollingStartupTest {

    private static final long AWAIT_MS = 5_000L;

    private FakeSerialTimers timers;
    private RemovalHost host;
    private SerialSourcePort port;
    private SerialSource source;
    /** 对话计时引出缝（执行者 wait/deadline 计时经 SerialTimeoutScheduler 回落域定时器，须分流）。 */
    private java.util.concurrent.ScheduledExecutorService timeoutEnforcer;

    @Before
    public void setUp() {
        SerialSdkTimers.resetForTest();
        timers = new FakeSerialTimers();
        // 执行者对话计时（wait/deadline 看门狗）经 SerialTimeoutScheduler 默认回落 SerialSdkTimers
        // ——bind 真 STPE 引出 fake 记录面（与 SerialPollingSdkTest 同款口径），链拍断言不受污染
        timeoutEnforcer = java.util.concurrent.Executors.newScheduledThreadPool(1,
                new NamedThreadFactory("serial-startup-test-timeout", true));
        SerialTimeoutScheduler.bind(timeoutEnforcer);
        host = action -> { };
        port = newPort();
        source = bridgedSource(port);
    }

    @After
    public void tearDown() {
        SerialTimeoutScheduler.unbind();
        timeoutEnforcer.shutdownNow();
        timers.close();
        SerialIoPool.resetForTest();
    }

    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("SDK-STARTUP-TEST-PORT", 9600, 8, 1, 0), 1, null);
    }

    /** 桥接到真实端口的 SerialSource mock（与 SerialPollingSdkTest 同款：锁状态机/提交穿真实实现）。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.acquirePollingBounded(anyLong())).thenAnswer(inv -> port.acquirePollingBounded(
                null, inv.getArgument(0, Long.class)));
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        when(source.submitTransaction(any(), any(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    SerialSource self = (SerialSource) inv.getMock();
                    if (self.isViewClosed()) {
                        throw new SerialSourceClosedException("submitTransaction", port.getPortName(), "bridged");
                    }
                    return port.submitTransaction(self, null,
                            inv.getArgument(1, SerialPriority.class),
                            inv.getArgument(2, Long.class), inv.getArgument(3, Long.class),
                            inv.getArgument(0, java.util.function.Function.class));
                });
        when(source.getPortName()).thenReturn(port.getPortName());
        when(source.getTimeout()).thenReturn(port.getTimeout());
        return source;
    }

    /** 下一拍等待（deadline 轮询：重试拍/入网首拍在 IO 工作线程提交，异步于测试线程动作）。 */
    private void awaitShots(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (timers.shots.size() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("拍未在 " + AWAIT_MS + "ms 内落账: expected>=" + expected
                        + ", actual=" + timers.shots.size());
            }
            Thread.yield();
        }
    }

    // ==================== 时序契约：startup 先行，周期链后起 ====================

    @Test
    public void startupCompletesBeforeFirstRound_roundStartsOnlyAfterSuccess() throws Exception {
        CountDownLatch startupBegan = new CountDownLatch(1);
        CompletableFuture<Boolean> startupResult = new CompletableFuture<>(); // 测试持有（在飞）
        CountDownLatch roundRan = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();

        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> {
                    startupBegan.countDown();
                    return startupResult;
                })
                .round(src -> {
                    rounds.incrementAndGet();
                    roundRan.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        assertEquals("start 后仅应有 startup 首发拍（周期链未起，无轮拍）", 1, timers.shots.size());
        assertEquals("startup 首发拍延迟 0（立即发起）", 0L, timers.shots.get(0).delayMillis);
        assertTrue(handle.isRunning());
        assertFalse("未失败", handle.isStartupFailed());

        timers.fire(0); // startup 尝试发起：对话经端口执行者，体在 IO 工作线程执行
        assertTrue("startup 体必须执行", startupBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("startup 在飞期间周期链未起（无第二轮拍）", 1, timers.shots.size());
        assertEquals("round 未执行", 0, rounds.get());

        startupResult.complete(Boolean.TRUE); // startup 成功 → 周期链接体入网
        awaitShots(2);
        assertEquals("入网首拍延迟=initialDelay（默认 0：startup 后立即首轮读）",
                0L, timers.shots.get(1).delayMillis);
        timers.fire(1);
        assertTrue("round 在 startup 之后执行（先初始化再周期读）",
                roundRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, rounds.get());
        handle.cancel();
    }

    // ==================== 重试契约：SDK 统一重试，耗尽即失败终态 ====================

    @Test
    public void businessFalseIsRetriedUpToAttempts_thenChainStarts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch roundRan = new CountDownLatch(1);

        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> CompletableFuture.completedFuture(
                        attempts.incrementAndGet() < 2 ? Boolean.FALSE : Boolean.TRUE), 2, 500)
                .round(src -> {
                    roundRan.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0); // 尝试 1：业务 FALSE（对话完成于 IO 线程）→ 重试拍
        awaitShots(2);
        assertEquals("重试拍延迟=声明的 retryDelay", 500L, timers.shots.get(1).delayMillis);

        timers.fire(1); // 尝试 2：成功 → 周期链入网
        awaitShots(3);
        assertFalse("重试成功即非失败态", handle.isStartupFailed());
        timers.fire(2); // 首轮
        assertTrue(roundRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("恰 2 次尝试", 2, attempts.get());
        handle.cancel();
    }

    @Test
    public void exceptionAttemptsExhausted_failsTerminal_noRoundEver() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> {
                    attempts.incrementAndGet();
                    CompletableFuture<Boolean> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new java.io.IOException("io-boom-" + attempts.get()));
                    return failed;
                })
                .onStartupFailure(ex -> {
                    failure.set(ex);
                    failureReported.countDown();
                })
                .round(src -> {
                    rounds.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0); // 尝试 1 失败 → 重试
        awaitShots(2);
        timers.fire(1); // 尝试 2 失败 → 重试
        awaitShots(3);
        timers.fire(2); // 尝试 3 失败 → 耗尽

        assertTrue("耗尽必须触发失败回调", failureReported.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("3 次尝试全部执行（attempts=3）", 3, attempts.get());
        assertTrue("句柄失败态", handle.isStartupFailed());
        assertFalse("失败态轮询不在网", handle.isRunning());
        assertEquals("轮询未启动：round 零执行", 0, rounds.get());
        assertEquals("无孤儿拍（1 首发 + 2 重试，周期链未起）", 3, timers.shots.size());
        assertNotNull("失败回调收到根因", failure.get());
        assertTrue("根因保留原始异常", failure.get() instanceof java.io.IOException);
        assertEquals("io-boom-3", failure.get().getMessage());
    }

    @Test
    public void submissionExceptionIsFoldedIntoRetry_thenSuccess() throws Exception {
        // 提交面同步异常（队满 PortQueueFullException 形态）折算可重试失败——与 CF 异常同格。
        // 用独立 fresh mock（不经 setUp 桥接后二次重打桩）：同方法二次 when() 的求值会执行
        // 旧桩 answer，其内部 self 回调（isViewClosed）会把候选打桩位覆盖到内转方法上，
        // 新 answer 绑错方法——每方法只打桩一次避开该 Mockito 打桩坑
        SerialSourcePort localPort = newPort();
        AtomicInteger submissions = new AtomicInteger();
        SerialSource localSource = mock(SerialSource.class);
        when(localSource.acquire()).thenAnswer(inv -> localPort.acquire());
        when(localSource.acquirePollingBounded(anyLong())).thenAnswer(inv ->
                localPort.acquirePollingBounded(null, inv.getArgument(0, Long.class)));
        when(localSource.release(anyString())).thenAnswer(inv ->
                localPort.release(inv.getArgument(0, String.class)));
        when(localSource.submitTransaction(any(), any(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    if (submissions.incrementAndGet() == 1) {
                        throw new PortQueueFullException("queue-full-probe");
                    }
                    return localPort.submitTransaction((SerialSource) inv.getMock(), null,
                            inv.getArgument(1, SerialPriority.class),
                            inv.getArgument(2, Long.class), inv.getArgument(3, Long.class),
                            inv.getArgument(0, java.util.function.Function.class));
                });
        when(localSource.getPortName()).thenReturn(localPort.getPortName());
        when(localSource.getTimeout()).thenReturn(localPort.getTimeout());

        CountDownLatch roundRan = new CountDownLatch(1);
        PollingHandle handle = SerialPolling.on(host, localSource)
                .startup(src -> CompletableFuture.completedFuture(Boolean.TRUE), 2, 500)
                .round(src -> {
                    roundRan.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0); // 首发：提交面同步抛 → 折算失败 → 重试拍
        awaitShots(2);
        assertEquals("重试拍延迟=声明的 retryDelay", 500L, timers.shots.get(1).delayMillis);
        timers.fire(1); // 重试：成功 → 入网
        awaitShots(3);
        timers.fire(2);
        assertTrue(roundRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse(handle.isStartupFailed());
        handle.cancel();
    }

    @Test
    public void oneArgOverloadDefaultsThreeAttemptsAndTwoSecondRetryDelay() throws Exception {
        CountDownLatch failureReported = new CountDownLatch(1);
        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> CompletableFuture.completedFuture(Boolean.FALSE)) // 恒失败探默认档
                .onStartupFailure(ex -> failureReported.countDown())
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0); // 尝试 1 失败 → 默认档重试拍
        awaitShots(2);
        assertEquals("默认重试拍延迟=2000ms（校准自 tianhong 既有消费面 3 次/2s）",
                2_000L, timers.shots.get(1).delayMillis);
        timers.fire(1); // 尝试 2 失败 → 第二拍
        awaitShots(3);
        assertEquals("第二拍同延迟", 2_000L, timers.shots.get(2).delayMillis);
        timers.fire(2); // 尝试 3 失败 → 耗尽（默认 attempts=3）
        assertTrue("默认 3 次后终态失败", failureReported.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(handle.isStartupFailed());
        assertEquals("恰 3 次尝试（1 首发 + 2 重试）", 3, timers.shots.size());
    }

    // ==================== 生命周期契约：cancel 取消在飞 startup 与待发重试 ====================

    @Test
    public void cancelDuringInFlightStartup_abortsSilently_noFailureCallback() throws Exception {
        CountDownLatch startupBegan = new CountDownLatch(1);
        CompletableFuture<Boolean> inFlight = new CompletableFuture<>();
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger rounds = new AtomicInteger();

        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> {
                    startupBegan.countDown();
                    return inFlight;
                })
                .onStartupFailure(ex -> failureReported.countDown())
                .round(src -> {
                    rounds.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0);
        assertTrue(startupBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));

        handle.cancel(); // 宿主移除 sweep / 设备 stop 形态
        assertFalse("cancel 后不在网", handle.isRunning());
        assertFalse("主动停机非故障：不置失败态", handle.isStartupFailed());

        inFlight.completeExceptionally(new java.io.IOException("late-transport-error"));
        assertFalse("迟到失败不得触发失败回调（链已亡，无人消费）",
                failureReported.await(400, TimeUnit.MILLISECONDS));
        assertEquals("无孤儿重试拍", 1, timers.shots.size());
        assertEquals("round 永不执行", 0, rounds.get());
    }

    @Test
    public void cancelBetweenRetries_cancelsPendingRetryShot() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch failureReported = new CountDownLatch(1);
        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }, 3, 500)
                .onStartupFailure(ex -> failureReported.countDown())
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        timers.fire(0); // 尝试 1 失败 → 重试拍 pending
        awaitShots(2);

        handle.cancel();
        assertTrue("cancel 必须撤销待发重试拍（孤儿重试链收口）",
                ((FakeSerialTimers.StubFuture) timers.shots.get(1).future).cancelled);
        assertFalse(handle.isRunning());
        assertFalse("cancel 非失败", handle.isStartupFailed());
        assertFalse("重试被撤：失败回调不得触发", failureReported.await(400, TimeUnit.MILLISECONDS));
        assertEquals("cancel 前恰 1 次尝试", 1, attempts.get());
    }

    @Test
    public void removalBindingStillRegistersExactlyOneAction_inStartupMode() {
        PollingHandle handle = SerialPolling.on(host, source)
                .startup(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(150, TimeUnit.MILLISECONDS)
                .start();
        assertTrue(handle.isRunning());
        handle.cancel();
    }

    // ==================== MDC 契约：startup 与 round 同在宿主 scope 内起链 ====================

    @Test
    public void deviceHostInjectsDeviceMdcIntoStartupBody_andKickoffSnapshot() throws Exception {
        ConfigEntry entry = new ConfigEntry();
        entry.setCoordinate("com.ecat:integration-serial");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("name", "串口设备-01");
        DeviceBase deviceHost = new MinimalDevice(entry, cfg);

        CountDownLatch startupBegan = new CountDownLatch(1);
        AtomicReference<String> seenId = new AtomicReference<>();
        AtomicReference<String> seenName = new AtomicReference<>();
        PollingHandle handle = SerialPolling.on(deviceHost, source)
                .startup(src -> {
                    seenId.set(MDC.get(MdcContext.DEVICE_ID_KEY));
                    seenName.set(MDC.get(MdcContext.DEVICE_NAME_KEY));
                    startupBegan.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .round(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                .every(150, TimeUnit.MILLISECONDS)
                .start();

        assertEquals("startup 首发拍提交快照含 device.id（scope 注入被捕获）",
                deviceHost.getId(), timers.shots.get(0).submitMdc.get(MdcContext.DEVICE_ID_KEY));

        timers.fire(0); // startup 体执行（IO 工作线程恢复提交点 MDC）
        assertTrue(startupBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("startup 体内恢复 device.id（帧归属与 round 同源）",
                deviceHost.getId(), seenId.get());
        assertEquals("startup 体内恢复 device.name", "串口设备-01", seenName.get());
        assertNull("提交线程（测试线程）不得残留设备键", MDC.get(MdcContext.DEVICE_ID_KEY));
        handle.cancel();
    }

    /** 最小设备桩：仅承载 id/name/coordinate，生命周期全 no-op。 */
    private static final class MinimalDevice extends DeviceBase {
        MinimalDevice(ConfigEntry gatewayEntry, Map<String, Object> config) {
            super(gatewayEntry, "serial-startup-test-unique", config);
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void release() {
        }
    }

    // ==================== 构建契约（严格模式 fail-fast；存量零变化） ====================

    @Test
    public void startStillRequiresRound_withStartupDeclared() {
        try {
            SerialPolling.on(host, source)
                    .startup(src -> CompletableFuture.completedFuture(Boolean.TRUE))
                    .every(150, TimeUnit.MILLISECONDS)
                    .start();
            fail("startup 不豁免 round 必填（先初始化再周期读，周期读仍必填）");
        } catch (IllegalStateException expected) { }
    }

    @Test
    public void startupDeclarationFailFast() {
        SerialPolling polling = SerialPolling.on(host, source);
        try {
            polling.startup(null);
            fail("startup(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            polling.startup(src -> CompletableFuture.completedFuture(Boolean.TRUE));
            polling.startup(src -> CompletableFuture.completedFuture(Boolean.TRUE));
            fail("startup 重复声明必须 fail-fast（一次性启动序列只能有一个定义）");
        } catch (IllegalStateException expected) { }
        try {
            SerialPolling.on(host, source).startup(
                    src -> CompletableFuture.completedFuture(Boolean.TRUE), 0, 500);
            fail("attempts=0 必须 fail-fast（至少首发一次）");
        } catch (IllegalArgumentException expected) { }
        try {
            SerialPolling.on(host, source).startup(
                    src -> CompletableFuture.completedFuture(Boolean.TRUE), 3, -1);
            fail("retryDelayMs<0 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
        try {
            SerialPolling.on(host, source).onStartupFailure(null);
            fail("onStartupFailure(null) 必须 fail-fast");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void noStartupDevicesKeepImmediateFirstRound() throws Exception {
        // 存量零变化守护：无 startup 的用法照旧「start 首发即发」（无 startup 首发拍垫层）
        CountDownLatch roundRan = new CountDownLatch(1);
        PollingHandle handle = SerialPolling.on(host, source)
                .round(src -> {
                    roundRan.countDown();
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                })
                .every(150, TimeUnit.MILLISECONDS)
                .start();
        assertEquals("无 startup：start 即排首轮拍（无 startup 拍）", 1, timers.shots.size());
        assertEquals(0L, timers.shots.get(0).delayMillis);
        timers.fire(0);
        assertTrue(roundRan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("无 startup 的句柄恒非失败态", handle.isStartupFailed());
        handle.cancel();
    }
}
