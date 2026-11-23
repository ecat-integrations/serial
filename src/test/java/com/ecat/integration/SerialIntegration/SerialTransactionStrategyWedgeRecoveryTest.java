package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Q-1/A2 挂死自愈家族的回归单测（serial IO 最小模型语义）。
 *
 * <p>问题形态（arch-review-20260815/29-detail-design/acceptance P0-evidence）：jSerialComm
 * 本地阻塞写（writeBytes）挂死时占死口执行线程；旧实现 release/recovery 排队在挂死车道上
 * → 幽灵锁 + 端口永久停更且不自愈。现行防线 = 硬超时看门狗：deadline 到点在<b>看门狗线程</b>
 * 直接跨线程 closePort 强拆挂死 fd（口执行线程从阻塞写中得救）→ 异常完成对话 future →
 * 事件驱动 RECOVERING 重开 → 排空 → 开下一场。锁已删除，「release 先于 recovery」的顺序
 * 契约随之退役——看门狗三步（强拆/完成 future/投递事件）各自幂等，不存在可丢失的释放链。
 *
 * <p>旧用例 → 新用例映射：releaseFires_whenIoLaneThreadIsWedged →
 * {@link #futureCompletesAndPortContinues_whenBodyWorkerHungForever}（口继续 = 无泄漏等价）；
 * portRecoveryTriggered_onTransactionHardTimeout → 看门狗跨线程 closePort 断言；
 * noPortRecovery_whenTransactionCompletesNormally → 正常完成零强拆。
 */
public class SerialTransactionStrategyWedgeRecoveryTest {

    /** 测试用短事务超时（生产默认见 resolveDefaultTransactionTimeoutMs） */
    private static final long TEST_TX_TIMEOUT_MS = 200L;
    /** 排空 + 重开 + 下一场完成的总上界（5s 余量足够 CI 慢机） */
    private static final long NEXT_CONV_BOUND_MS = 5_000L;

    /** 注入 mock jSerialComm 口（isOpen 可随 closePort 翻转，供恢复路径断言）。 */
    private SerialSourcePort newPortWithMockFd(AtomicBoolean fdOpen, SerialPort mockFd) {
        when(mockFd.isOpen()).thenAnswer(inv -> fdOpen.get());
        when(mockFd.bytesAvailable()).thenReturn(0);
        when(mockFd.closePort()).thenAnswer(inv -> {
            fdOpen.set(false);
            return true;
        });
        when(mockFd.writeBytes(any(byte[].class), anyLong()))
                .thenAnswer(inv -> inv.<byte[]>getArgument(0).length);
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("WEDGE-REC-PORT", 9600, 8, 1, 0), 3, null);
        port.serialPort = mockFd;
        return port;
    }

    /**
     * 【RED：Q-1/A2 等价面】事务体在 IO 工作线程永久悬挂时：对话 future 必须在 deadline 内
     * 异常完成（看门狗线程执法，不依赖悬挂体），且口必须随后接受下一场对话——「口被一场
     * 悬挂对话永久占住」结构性不可能（旧幽灵锁事故面的等价判据）。
     */
    @Test
    public void futureCompletesAndPortContinues_whenBodyWorkerHungForever() throws Exception {
        SerialSourcePort port = newPortWithMockFd(new AtomicBoolean(true), mock(SerialPort.class));
        SerialSource source = new SerialSource(port, "wedge-test");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        try {
            result.get(TEST_TX_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
            fail("期望返回 future 因事务硬超时异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应为 TimeoutException，实际: " + ee.getCause(),
                    ee.getCause() instanceof java.util.concurrent.TimeoutException);
        }
        assertTrue("deadline 后口必须接受下一场（幽灵锁事故面的等价判据）",
                SerialTransactionStrategy.executeWithLambda(
                        source, src -> CompletableFuture.completedFuture(true))
                        .get(NEXT_CONV_BOUND_MS, TimeUnit.MILLISECONDS));
    }

    /** 契约：事务硬超时（端口 IO 挂死强证据）必须触发看门狗跨线程 closePort 强拆。 */
    @Test
    public void portRecoveryTriggered_onTransactionHardTimeout() {
        AtomicBoolean fdOpen = new AtomicBoolean(true);
        SerialPort mockFd = mock(SerialPort.class);
        SerialSourcePort port = newPortWithMockFd(fdOpen, mockFd);
        SerialSource source = new SerialSource(port, "wedge-verify");

        // 挂死对话：deadline 到点即异常完成（fire-and-forget，.get() 必然 ExecutionException）
        SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        // 看门狗契约=必然跨线程强拆：以 fdOpen 翻转为确定性事件（AtomicBoolean 无锁读）。
        // 不用 mockito verify(timeout) 轮询——它持有 mock 内部锁，会把看门狗线程的
        // isOpen 记录调用阻塞到 verify 超时之后（10s 晚点假象，非产线行为）。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fdOpen.get() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertFalse("看门狗必须在 deadline 后跨线程强拆 fd（closePort 被调）", fdOpen.get());
        org.mockito.Mockito.verify(mockFd, org.mockito.Mockito.times(1)).closePort();
    }

    /** 契约：事务正常完成时不得触发强拆（强拆有代价，仅挂死时用）。 */
    @Test
    public void noPortRecovery_whenTransactionCompletesNormally() throws Exception {
        AtomicBoolean fdOpen = new AtomicBoolean(true);
        SerialPort mockFd = mock(SerialPort.class);
        SerialSourcePort port = newPortWithMockFd(fdOpen, mockFd);
        SerialSource source = new SerialSource(port, "wedge-normal");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true), TEST_TX_TIMEOUT_MS);

        assertTrue(result.get(TEST_TX_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS));
        verify(mockFd, org.mockito.Mockito.timeout(1000).times(0)).closePort();
    }
}
