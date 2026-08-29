package com.ecat.integration.SerialIntegration;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.NamedThreadFactory;

/**
 * 【同线程嵌套取锁 fail-fast 守卫】36 号设计（方案 D·形态 A）：vaisala 事故
 * （bug-record-20260829-082100）的 SDK 层加固。事务体经 SerialTransactionStrategy
 * 的 executeHeld 在发起线程上同步执行——round/事务临界体内再经 executeWithLambda/
 * executePolling 二次取锁时，等待者与持有者是同一线程（key 同为 毫秒-线程ID），
 * condition.await 永等不到自己的 release：旧形态阻塞到超时返 null（live 实证同一线程
 * 静默空转 5h44m），守卫改为立即抛且锁状态原样。
 *
 * <p>契约：
 * <ul>
 *   <li>acquire 与 tryAcquire 两入口同守卫（tryAcquire 嵌套同样自死锁）；</li>
 *   <li>命中抛 IllegalStateException 且锁状态不因抛出改变（原持有关系完好，异线程
 *       不受影响）；release 后同线程再取合法；</li>
 *   <li>守卫检查点在幽灵锁收割<b>之后</b>——同线程的陈年幽灵锁先收割后授予，不抛
 *       （保 {@code acquireReapsGhostLock_whenHeldBeyondThreshold} 契约）；</li>
 *   <li>端到端：round 临界体内嵌套 executeWithLambda → round 立即失败（非 5s park
 *       超时）、轮询链不注销。</li>
 * </ul>
 *
 * <p>确定性同步 = CountDownLatch（负向断言用有界 await 返回值），无 Thread.sleep；
 * 跨收割阈值用 latch.await 推进墙钟（与 {@code SerialSourcePortGhostLockReapTest}
 * 同范式）。端到端用例复用 {@code SerialPollingSdkTest} 的真实 port 桥接范式。
 */
public class SerialSourcePortSameThreadNestedAcquireGuardTest {

    private static final String PORT_NAME = "NESTED-GUARD-PORT";
    /** 顺序约束用例的收割阈值：显著小于跨阈值等待，跨阈值的墙钟推进由 latch.await 承担。 */
    private static final long SHORT_REAP_THRESHOLD_MS = 100;
    private static final long CROSS_THRESHOLD_WAIT_MS = 300;
    /**
     * 非收割用例的收割阈值：秒级大窗对并行构建下的调度噪声免疫（守卫/异线程用例的负向断言
     * 「锁仍被持有」依赖不误入收割分支，100ms 窗在线程跃迁延迟下可能被推过阈值）。
     * 收割边界两侧由 {@code SerialSourcePortGhostLockReapTest} 与顺序约束用例钉死。
     */
    private static final long WIDE_REAP_THRESHOLD_MS = 60_000;
    /** 「立即抛」判别窗：远小于旧形态的 5s 阻塞 park（acquire() 默认超时）。 */
    private static final long IMMEDIATE_BOUND_MS = 1_000;
    private static final long AWAIT_MS = 5_000;
    private static final long PERIOD_MS = 150L;

    private java.util.concurrent.ScheduledExecutorService timers;
    private SerialSourcePort port;

    @Before
    public void setUp() {
        SerialSdkTimers.resetForTest();
        timers = java.util.concurrent.Executors.newScheduledThreadPool(2,
                new NamedThreadFactory("nested-guard-test", true));
        SerialSdkTimers.bindForTest(SerialSdkTimers.forScheduledExecutor(timers));
        port = newPort(WIDE_REAP_THRESHOLD_MS);
    }

    @After
    public void tearDown() {
        SerialSdkTimers.unbindForTest();
        timers.shutdownNow();
        SerialIoPool.resetForTest();
    }

    /** 真实锁状态机端口（不 openPort，构造零 jSerialComm 副作用；同 GhostLockReapTest 范式）。 */
    private SerialSourcePort newPort(long ghostReapThresholdMs) {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo(PORT_NAME, 9600, 8, 1, 0), 1, null);
        port.setGhostReapThresholdMsForTest(ghostReapThresholdMs);
        return port;
    }

    // ==================== ① 同线程二次 acquire：立即抛 + 锁状态未破坏 ====================

    /**
     * 守卫主契约：同线程双取立即抛（微秒级，非旧形态等满超时返 null）；异常消息带全诊断
     * 要素；抛后锁状态未破坏——异线程锁忙照常 null、原 key release 仍真、release 后异线程可获取。
     */
    @Test
    public void sameThreadDoubleAcquireThrowsImmediately_lockStateIntact() throws Exception {
        String heldKey = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("前置：首次 acquire 应取得锁", heldKey);

        long startNanos = System.nanoTime();
        try {
            port.acquire(5, TimeUnit.SECONDS);
            fail("同线程二次 acquire 必须立即抛（旧形态为阻塞到超时返 null 的自死锁）");
        } catch (IllegalStateException expected) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue("必须立即抛而非等满超时（实测 " + elapsedMs + "ms，界 " + IMMEDIATE_BOUND_MS + "ms）",
                    elapsedMs < IMMEDIATE_BOUND_MS);
            String message = expected.getMessage();
            assertTrue("消息须含端口标识: " + message, message.contains(PORT_NAME));
            assertTrue("消息须含持有者 key: " + message, message.contains(heldKey));
            assertTrue("消息须含持锁线程名: " + message,
                    message.contains(Thread.currentThread().getName()));
            assertTrue("消息须含持锁时长要素: " + message, message.contains("已持锁"));
            assertTrue("消息须含修复指引（事务入口直发）: " + message,
                    message.contains("executeWithLambda/executePolling"));
        }

        // 抛后锁状态未破坏（持锁期间）：异线程 tryAcquire 是锁忙放弃（null）而非误判守卫
        final String[] crossTry = new String[1];
        runOnHelperThread("cross-try", () -> crossTry[0] = port.tryAcquire());
        assertNull("守卫只对同线程命中；异线程锁忙照常立即返 null", crossTry[0]);

        // 原持有关系原样：release 仍真
        assertTrue("守卫命中不得改变锁状态——原 key release 仍有效", port.release(heldKey));

        // release 后异线程可正常获取（锁状态机未被守卫污染）
        final String[] crossKey = new String[1];
        runOnHelperThread("cross-acquire", () -> crossKey[0] = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertNotNull("release 后异线程必须能正常获取", crossKey[0]);
        assertTrue(port.release(crossKey[0]));
    }

    // ==================== ② 同线程嵌套 tryAcquire：同守卫 ====================

    /** tryAcquire 入口同样嵌套自死锁（不 park 但持有关系会被无声破坏）：同守卫立即抛。 */
    @Test
    public void sameThreadNestedTryAcquireThrows() {
        String heldKey = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(heldKey);

        try {
            port.tryAcquire();
            fail("同线程嵌套 tryAcquire 必须立即抛");
        } catch (IllegalStateException expected) {
            assertTrue("消息须标识 tryAcquire 入口: " + expected.getMessage(),
                    expected.getMessage().contains("tryAcquire"));
        }
        assertTrue("抛后锁状态未变", port.release(heldKey));
    }

    // ==================== ③ 异线程正常路径不受影响 ====================

    /** 非持锁线程取锁不触发守卫：锁忙照常（tryAcquire null / acquire 超时 null），释放后可得。 */
    @Test
    public void crossThreadPathsUnaffected() throws Exception {
        final String[] heldKey = new String[1];
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch releaseSignal = new CountDownLatch(1);
        final Throwable[] holderError = new Throwable[1];
        Thread holder = new Thread(() -> {
            try {
                heldKey[0] = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
                held.countDown();
                releaseSignal.await(AWAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable x) {
                holderError[0] = x;
            } finally {
                if (heldKey[0] != null) {
                    port.release(heldKey[0]);
                }
            }
        }, "cross-holder");
        holder.start();
        assertTrue("持锁线程必须在期限内取得锁", held.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertNull(holderError[0]);

        // 主线程此刻非持有者：tryAcquire 锁忙 null（正常放弃，非守卫抛）
        assertNull("异线程锁忙 tryAcquire 照常返 null", port.tryAcquire());
        // 异线程有限等待照常超时 null（阈值内不收割 + 不抛）
        assertNull("异线程 acquire 阈值内照常超时返 null",
                port.acquire(50, TimeUnit.MILLISECONDS));

        releaseSignal.countDown();
        holder.join(AWAIT_MS);

        // 持有者已释放：主线程（曾经的异线程）正常获取
        String next = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("释放后其他线程必须能正常获取", next);
        port.release(next);
    }

    // ==================== ④ release 后同线程再取合法 ====================

    /** 守卫只挡「未释放期间」的嵌套：release 清记账后同线程重取是正常串行事务。 */
    @Test
    public void reacquireAfterReleaseIsLegal() {
        String first = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        assertTrue(port.release(first));

        String second = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("release 后同线程再取必须合法（守卫不得误伤正常串行事务）", second);
        assertTrue(port.release(second));

        String third = port.tryAcquire();
        assertNotNull("release 后同线程 tryAcquire 同样合法", third);
        port.release(third);
    }

    // ==================== ⑤ 顺序约束钉死：先收割后守卫 ====================

    /**
     * 守卫检查点必须在幽灵锁收割之后：同线程持锁跨收割阈值后二次取锁 = 陈年幽灵锁形态，
     * 应被收割后授予（不抛）——守卫若在收割之前，此场景会误抛并破坏
     * {@code acquireReapsGhostLock_whenHeldBeyondThreshold} 既有契约。
     */
    @Test
    public void sameThreadStaleGhostLockIsReapedBeforeGuard_grantsInsteadOfThrowing() throws Exception {
        SerialSourcePort port = newPort(SHORT_REAP_THRESHOLD_MS);
        String ghostKey = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(ghostKey);

        // 等墙钟跨过收割阈值（latch 永不 countDown，await 到点返回 false 属预期，取墙钟推进语义）
        new CountDownLatch(1).await(CROSS_THRESHOLD_WAIT_MS, TimeUnit.MILLISECONDS);

        String revived = port.acquire(AWAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("跨阈值的同线程陈年幽灵锁应先收割后授予（守卫在收割之后，非抛）", revived);

        // 收割后旧 key 已不在锁状态机上（迟到 release 无效 = 证明确已清零，非双持有）
        assertTrue(port.release(revived));
        assertFalse("幽灵 key 的迟到 release 应无效", port.release(ghostKey));
    }

    // ==================== ⑥ 端到端：round 临界体内嵌套 executeWithLambda ====================

    /**
     * vaisala 事故形态端到端：SDK 的 round 体在 executePolling 已持锁的临界区内（同线程）
     * 再经 executeWithLambda 二次取锁。守卫立即抛 → 外层事务异常完成 → round 立即失败
     * （旧形态：发起线程 park 5s 超时后才失败）+ ERROR 响亮报错 → 轮询链不注销，
     * 下一轮照常执行。日志观察须摘下 logback 全局限频 TurboFilter（生产 WARN/ERROR 限频
     * 策略有独立测试），范式同 {@code SerialPollingSdkTest} 断连状态转移行用例。
     */
    @Test
    public void nestedAcquireInsideRoundFailsRoundImmediately_pollingChainSurvives() throws Exception {
        RemovalHost device = action -> { };
        SerialSource source = bridgedSource(port);

        ch.qos.logback.classic.Logger pollingLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SerialPolling.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        pollingLogger.addAppender(appender);
        java.util.List<ch.qos.logback.classic.turbo.TurboFilter> turboFilters = detachTurboFilters();
        try {
            CountDownLatch errorCallback = new CountDownLatch(1);
            CountDownLatch secondRound = new CountDownLatch(1);
            AtomicReference<Throwable> seen = new AtomicReference<>();
            AtomicInteger rounds = new AtomicInteger();

            long startNanos = System.nanoTime();
            PollingHandle handle = SerialPolling.on(device, source)
                    .round(src -> {
                        if (rounds.incrementAndGet() > 1) {
                            secondRound.countDown();
                            return CompletableFuture.completedFuture(true);
                        }
                        // 事故形态：round 临界体内（SDK 已经 executePolling 持锁）二次取锁
                        return SerialTransactionStrategy.executeWithLambda(src,
                                inner -> CompletableFuture.completedFuture(true));
                    })
                    .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                    .onRound((result, ex) -> {
                        if (ex != null && seen.compareAndSet(null, ex)) {
                            errorCallback.countDown();
                        }
                    })
                    .start();
            try {
                assertTrue("嵌套取锁轮必须失败并回调异常",
                        errorCallback.await(AWAIT_MS, TimeUnit.MILLISECONDS));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                assertTrue("round 必须立即失败而非 park 满超时后失败（实测 " + elapsedMs
                        + "ms，界 4000ms；旧形态 nested acquire 默认 5s park）", elapsedMs < 4_000);

                Throwable root = seen.get();
                while (root instanceof CompletionException && root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue("根因必须是同线程嵌套守卫的 IllegalStateException（实际: " + root + "）",
                        root instanceof IllegalStateException
                                && root.getMessage().contains("同线程嵌套取锁"));

                assertTrue("守卫轮必须打 ERROR 响亮报错（per-round 全栈照打，可 grep 定位根因）",
                        appender.list.stream().anyMatch(e ->
                                e.getLevel() == ch.qos.logback.classic.Level.ERROR
                                        && e.getFormattedMessage().contains("polling round failed")));
                assertTrue("守卫轮后轮询链不得注销（永不注销）", handle.isRunning());
                assertTrue("后续轮必须照常执行", secondRound.await(AWAIT_MS, TimeUnit.MILLISECONDS));
            } finally {
                handle.cancel();
            }
        } finally {
            restoreTurboFilters(turboFilters);
            pollingLogger.detachAppender(appender);
        }
    }

    // ==================== 装配 ====================

    /**
     * 摘下 logback 全局 TurboFilter（ecat-core logback.xml 的 ErrorRateLimitFilter 对
     * WARN/ERROR 做 3s 窗口限频去重）：本测试断言守卫轮的 ERROR 转移行，须在无限频的
     * 通道上观察（范式同 {@code SerialPollingSdkTest}），用毕恢复。
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
     * 辅助线程跑一段取锁体并等待完成（总线竞态测试的确定性编排范式：latch 等事件发生，
     * 异常捕获出线程外断言）。抛出的守卫异常不是本方法的被测对象，故记录后由调用方断言。
     */
    private void runOnHelperThread(String name, Runnable body) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable x) {
                error[0] = x;
            } finally {
                done.countDown();
            }
        }, name);
        t.start();
        assertTrue("辅助线程必须在期限内返回", done.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        if (error[0] instanceof RuntimeException) {
            throw (RuntimeException) error[0];
        }
        assertNull("辅助线程不应抛非预期异常", error[0]);
    }

    /** 桥接到真实端口的 SerialSource mock：executePolling/executeWithLambda 的锁状态机穿过真实实现。 */
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
