package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;

import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

/**
 * 【RED：Q-1/Q-2 二轮】两个 live 实证缺陷的回归：
 *
 * <p>① release/recovery 顺序：修复前 recovery（closePort/openPort，可能阻塞）先于 release
 * 执行——recovery 中途阻塞会无限期推迟 release，currentKey 直接升级为幽灵锁
 *（live 实证持锁 45min+）。契约：硬超时路径必须先 release 再 recoverWedgedPort。
 *
 * <p>② 计时任务提交失败（调度器排队达上限拒绝）：修复前 timer 永不完成 → 事务 future
 * 永不完成 → whenComplete 不触发 → release 永不执行 → 幽灵锁（live 队列 4096 打满期间
 * 实证）。契约：提交抛异常时 withHardTimeout 必须 fail-fast 异常完成，保证 release 链必达。
 */
public class SerialTransactionStrategyRecoveryOrderTest {

    private static final long TEST_TX_TIMEOUT_MS = 200L;
    private static final long VERIFY_TIMEOUT_MS = 3000L;

    @After
    public void unbindScheduler() {
        SerialTimeoutScheduler.unbind();
    }

    /** 契约①：硬超时路径 release 必须先于 recoverWedgedPort（recovery 阻塞不得推迟锁释放）。 */
    @Test
    public void releaseBeforeRecovery_onTransactionHardTimeout() {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-order");

        SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        verify(source, timeout(VERIFY_TIMEOUT_MS)).release("key-order");
        verify(source, timeout(VERIFY_TIMEOUT_MS)).recoverWedgedPort(anyString());
        InOrder inOrder = inOrder(source);
        inOrder.verify(source).release("key-order");
        inOrder.verify(source).recoverWedgedPort(anyString());
    }

    /** 契约②：计时任务提交抛 REE 时事务 future 必须快速异常完成且 release 必达（修复前 = 幽灵锁）。 */
    @Test
    public void hardTimeoutFailsFast_whenTimerSubmissionRejected() throws Exception {
        SerialTimeoutScheduler.bind(newRejectingScheduler());
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-reject");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        // 提交即拒绝 → fail-fast（秒级返回而非无限挂起），且 release 已执行
        try {
            result.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("计时链丢失时事务 future 不应成功完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 链应含提交失败标记，实际: " + ee.getCause(),
                    ee.getCause() instanceof CompletionException
                            || ee.getCause() instanceof IllegalStateException
                            || ee.getCause() instanceof RejectedExecutionException);
        }
        verify(source, timeout(VERIFY_TIMEOUT_MS)).release("key-reject");
    }

    /** schedule 一律抛 REE 的调度器（复刻排队达上限的同步拒绝）。 */
    private ScheduledExecutorService newRejectingScheduler() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                throw new RejectedExecutionException("queue full (test)");
            }
        };
    }
}
