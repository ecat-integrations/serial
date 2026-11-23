package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

/**
 * 【Q-1/Q-2 二轮事故面】计时链丢失的 fail-fast 契约（serial IO 最小模型语义）。
 *
 * <p>事故面：计时任务提交失败（调度器排队达上限拒绝/停机）时，若不处理，对话的
 * deadline 计时器永不挂载 → future 永不完成 → 口执行线程永等一场对话 → 端口瘫痪
 * （live 实证队列 4096 打满期间的幽灵锁）。契约：计时提交抛异常时必须立即 fail-fast
 * 异常完成对话 future（排队等待计时与执行 deadline 计时两条路径都覆盖）。
 *
 * <p>旧「release 先于 recovery 顺序」契约随锁删除退役：看门狗三步（跨线程强拆 fd/
 * 完成 future/投递事件）各自幂等，不存在可丢失的释放链（SerialTransactionStrategy
 * WedgeRecoveryTest 承接挂死自愈面）。
 */
public class SerialTransactionStrategyRecoveryOrderTest {

    private static final long TEST_TX_TIMEOUT_MS = 200L;
    private static final long VERIFY_TIMEOUT_MS = 3_000L;

    @After
    public void unbindScheduler() {
        SerialTimeoutScheduler.unbind();
    }

    /**
     * 契约：计时任务提交抛 REE 时对话 future 必须快速异常完成（fail-fast，秒级返回而非
     * 无限挂起）——口执行线程不因计时链丢失而永等一场对话。
     */
    @Test
    public void hardTimeoutFailsFast_whenTimerSubmissionRejected() throws Exception {
        SerialTimeoutScheduler.bind(newRejectingScheduler());
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("REJECT-PORT", 9600, 8, 1, 0), 3, null);
        SerialSource source = new SerialSource(port, "reject-test");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        try {
            result.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            fail("计时提交被拒：对话 future 应快速异常完成（fail-fast）");
        } catch (ExecutionException ee) {
            assertTrue("fail-fast 应为 IllegalStateException（提交即拒绝非超时），实际: " + ee.getCause(),
                    ee.getCause() instanceof IllegalStateException);
        }
        // 口必须保持服务能力：恢复调度器后下一场正常完成
        SerialTimeoutScheduler.unbind();
        assertTrue(SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true))
                .get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /** 恒拒绝的调度器替身（模拟调度器排队达上限/停机）。 */
    private ScheduledExecutorService newRejectingScheduler() {
        ScheduledExecutorService rejecting = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        rejecting.shutdownNow();
        return rejecting;
    }
}
