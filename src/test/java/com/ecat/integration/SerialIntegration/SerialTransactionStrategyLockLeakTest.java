package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * B5 串口锁泄漏家族的回归单测（serial IO 最小模型语义：锁已删除，风险本体 =
 * 「对话 future 必然终结 + 口必然继续下一场」）。
 *
 * <p>问题（B5，bug-record-20260619221536）：旧实现把 {@code release(key)} 绑定在 lambda
 * future 的 whenComplete 上——future 永不 complete 时锁永久泄漏，设备掉线再上线无法
 * 自恢复，必须重启 core。现行防线 = 执行者看门狗：每场对话有 deadline（自授予起算），
 * 到点由看门狗跨线程强拆 fd 并异常完成 future，排空后开下一场——「口被一场对话永久
 * 占住」结构性不可能。
 *
 * <p>旧用例 → 新用例映射：releaseFires_whenLambdaFutureNeverCompletes →
 * {@link #conversationCompletesOnDeadline_whenBodyNeverCompletes}；
 * releaseFires_whenLambdaFutureCompletesNormally / ThrowsSynchronously → 正常/同步抛完成
 * 两用例 + 逐个加「口接受下一场」断言（无泄漏等价判据）；
 * returnsFailedFuture_whenAcquireReturnsNull → 等锁预算耗尽失败用例
 * （SerialPollingNonBlockingTest.executeWithLambdaKeepsBoundedWait_whenPortBusy）。
 *
 * @author coffee
 */
public class SerialTransactionStrategyLockLeakTest {

    /** 测试用短事务超时（生产默认见 resolveDefaultTransactionTimeoutMs / Const.READ_TIMEOUT_MS） */
    private static final long TEST_TX_TIMEOUT_MS = 200L;
    /** 排空静默上界：看门狗收场 + 重开 + 150ms 静默后口即接受下一场，5s 余量足够 CI 慢机 */
    private static final long NEXT_CONV_BOUND_MS = 5_000L;

    /**
     * 【RED：复现 B5】lambda 返回的 future 永不 complete（等价「后端死亡、异步串口读永久
     * 挂起」）时，对话 future 必须在 deadline 内异常完成（TimeoutException），且口必须
     * 随后接受下一场对话（无泄漏等价判据：下一场正常完成即证明口未被永久占住）。
     */
    @Test
    public void conversationCompletesOnDeadline_whenBodyNeverCompletes() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource source = newSource(port);

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        try {
            result.get(TEST_TX_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
            fail("期望返回 future 因事务硬超时异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应为 TimeoutException，实际: " + ee.getCause(),
                    ee.getCause() instanceof TimeoutException);
        }
        assertTrue("deadline 后口必须接受下一场（无泄漏等价判据）",
                SerialTransactionStrategy.executeWithLambda(
                        source, src -> CompletableFuture.completedFuture(true))
                        .get(NEXT_CONV_BOUND_MS, TimeUnit.MILLISECONDS));
    }

    /** 契约：lambda future 正常完成时，结果透传且口立即接受下一场。 */
    @Test
    public void completesNormally_thenPortAcceptsNextConversation() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource source = newSource(port);

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true), TEST_TX_TIMEOUT_MS);

        assertTrue("正常完成应透传 true", result.get(2, TimeUnit.SECONDS));
        assertTrue("下一场应正常完成",
                SerialTransactionStrategy.executeWithLambda(
                        source, src -> CompletableFuture.completedFuture(true))
                        .get(NEXT_CONV_BOUND_MS, TimeUnit.MILLISECONDS));
    }

    /** 契约：lambda 同步抛异常时，对话异常完成且口接受下一场（既有行为不回归）。 */
    @Test
    public void completesExceptionally_whenBodyThrowsSynchronously_thenPortContinues() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource source = newSource(port);

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> {
                    throw new RuntimeException("sync boom");
                }, TEST_TX_TIMEOUT_MS);

        try {
            result.get(2, TimeUnit.SECONDS);
            fail("期望异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应含同步异常信息", ee.getCause().getMessage().contains("sync boom"));
        }
        assertTrue("下一场应正常完成",
                SerialTransactionStrategy.executeWithLambda(
                        source, src -> CompletableFuture.completedFuture(true))
                        .get(NEXT_CONV_BOUND_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * 契约：串行性保持——两场对话不并发（第二场的开始时间不早于第一场的完成点），
     * 同口一场一场地来是最小模型的根契约。
     */
    @Test
    public void conversationsRunOneAtATime_onSamePort() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource source = newSource(port);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlap = new AtomicInteger();

        CompletableFuture<Boolean> first = SerialTransactionStrategy.executeWithLambda(source, src -> {
            if (inFlight.incrementAndGet() > 1) {
                overlap.incrementAndGet();
            }
            return delay(80L).thenApply(v -> {
                inFlight.decrementAndGet();
                return true;
            });
        });
        CompletableFuture<Boolean> second = SerialTransactionStrategy.executeWithLambda(source, src -> {
            if (inFlight.incrementAndGet() > 1) {
                overlap.incrementAndGet();
            }
            return delay(20L).thenApply(v -> {
                inFlight.decrementAndGet();
                return true;
            });
        });
        assertTrue(first.get(5, TimeUnit.SECONDS));
        assertTrue(second.get(5, TimeUnit.SECONDS));
        assertFalse("同口对话不得并发（重叠计数必须为 0）", overlap.get() > 0);
    }

    /**
     * 契约：默认（2 参重载）事务硬超时由设备配置的串口超时派生（× {@link SerialTransactionStrategy#TRANSACTION_TIMEOUT_FACTOR}），
     * 与应答策略 {@code source.getTimeout() > 0 ? source.getTimeout() : Const.READ_TIMEOUT_MS} 模式一致。
     */
    @Test
    public void defaultTimeout_derivedFromDeviceConfigTimeout() {
        SerialSource source = mock(SerialSource.class);
        when(source.getTimeout()).thenReturn(300);

        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(source);

        assertEquals((long) (300 * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }

    /** 契约：设备串口超时无效（≤0）时，默认事务超时回退 Const.READ_TIMEOUT_MS × 倍数。 */
    @Test
    public void defaultTimeout_fallsBackToConst_whenDeviceTimeoutInvalid() {
        SerialSource source = mock(SerialSource.class);
        when(source.getTimeout()).thenReturn(0);

        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(source);

        assertEquals((long) (Const.READ_TIMEOUT_MS * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }

    /** 契约：source 为 null 时不应 NPE，回退 Const.READ_TIMEOUT_MS × 倍数。 */
    @Test
    public void defaultTimeout_fallsBackToConst_whenSourceNull() {
        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(null);
        assertEquals((long) (Const.READ_TIMEOUT_MS * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }

    // ==================== helpers ====================

    /** 真实端口对象（容量 3；构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("LOCKLEAK-PORT", 9600, 8, 1, 0), 3, null);
    }

    /** 真实视图（挂靠注册对假端口名 open 失败无害）。 */
    private SerialSource newSource(SerialSourcePort port) {
        return new SerialSource(port, "lock-leak-test");
    }

    /** 域定时器单发延迟（锁外留隙惯用法；到期正常完成）。 */
    private CompletableFuture<Void> delay(long ms) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SerialSdkTimers.fireAfter(() -> future.complete(null), ms);
        return future;
    }
}
