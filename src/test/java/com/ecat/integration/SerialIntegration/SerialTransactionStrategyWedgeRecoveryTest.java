package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

/**
 * Q-1/A2（P0 验收打回）串口锁挂死泄漏的 TDD 单测。
 *
 * <p>问题形态（arch-review-20260815/29-detail-design/acceptance P0-evidence-jstack-a2-fail/a5-wedge）：
 * jSerialComm 本地阻塞写（writeBytes）挂死时占用 per-port 单线程 IO 车道；旧实现把
 * {@code release(key)} 绑定在事务 future 的 {@code whenCompleteAsync(ioExecutor)} 上——release
 * 回调被排队在<b>已被挂死写占用的同一车道</b>后面，即使事务级硬超时已使 future complete，
 * release 也永远执行不到 → 端口锁（currentKey）成为幽灵锁，后续 acquire 永远超时，
 * 6/6 ecat-sched-worker 同型等待、设备永久停更且故障移除后不自愈。
 *
 * <p>修复契约：
 * <ol>
 *   <li>release 必须在<b>完成 future 的线程上内联执行</b>（硬超时路径 = 超时调度线程），
 *       不得排队到可能挂死的 IO 车道；</li>
 *   <li>事务硬超时（TimeoutException）= 端口 IO 挂死强证据 → 触发
 *       {@code SerialSource.recoverWedgedPort}（close+reopen 强拆挂死的本地阻塞写）；
 *       正常完成的事务不得触发强拆。</li>
 * </ol>
 *
 * @author coffee
 */
public class SerialTransactionStrategyWedgeRecoveryTest {

    /** 测试用短事务超时（生产默认见 resolveDefaultTransactionTimeoutMs） */
    private static final long TEST_TX_TIMEOUT_MS = 200L;

    private final CountDownLatch laneRelease = new CountDownLatch(1);

    /** 构造一个「单线程被钉死」的 IO 车道：首个任务 latch 等待，后续任务全部排队（模拟挂死 writeBytes）。 */
    private ExecutorService newWedgedLane() {
        ExecutorService lane = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wedged-lane");
            t.setDaemon(true);
            return t;
        });
        lane.submit(() -> {
            try {
                laneRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return lane;
    }

    @After
    public void releaseWedgedLane() {
        laneRelease.countDown();
    }

    /**
     * 【RED：Q-1/A2 幽灵锁】IO 车道线程被挂死写占用时，release 仍必须在硬超时后及时执行。
     * 修复前：release 排队在挂死车道上 → verify 超时失败 = 复现 A2/A5 的 SerialSourcePort.acquire
     * 全员超时 wedge。
     */
    @Test
    public void releaseFires_whenIoLaneThreadIsWedged() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.getIoExecutor()).thenReturn(newWedgedLane());
        when(source.acquire()).thenReturn("key-wedged");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        // release 不依赖 IO 车道：硬超时（超时调度线程）内联触发（修复前此处超时失败 = 复现幽灵锁）
        verify(source, timeout(TEST_TX_TIMEOUT_MS + 2000).times(1)).release("key-wedged");

        try {
            result.get(TEST_TX_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            fail("期望返回 future 因事务硬超时异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应为 TimeoutException，实际: " + ee.getCause(),
                    ee.getCause() instanceof java.util.concurrent.TimeoutException);
        }
    }

    /** 契约：事务硬超时（端口 IO 挂死强证据）必须触发端口强拆自愈（close+reopen）。 */
    @Test
    public void portRecoveryTriggered_onTransactionHardTimeout() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.getIoExecutor()).thenReturn(java.util.concurrent.ForkJoinPool.commonPool());
        when(source.acquire()).thenReturn("key-timeout");

        SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        verify(source, timeout(TEST_TX_TIMEOUT_MS + 2000).times(1))
                .recoverWedgedPort(anyString());
    }

    /** 契约：事务正常完成时 release 正常触发，且不得误触发端口强拆（强拆有代价，仅挂死时用）。 */
    @Test
    public void noPortRecovery_whenTransactionCompletesNormally() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-normal");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true), TEST_TX_TIMEOUT_MS);

        assertTrue(result.get(TEST_TX_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS));
        verify(source, timeout(1000).times(1)).release("key-normal");
        verify(source, never()).recoverWedgedPort(anyString());
    }
}
