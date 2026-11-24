package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * 残留写防线回归单测（serial IO 最小模型语义：事务代数闸门退役，防线 = 「严格串行 +
 * 异常结束排空 + 排空窗拒写」）。
 *
 * <p>事故面（bugs/bug-record-20260826-093000 Q1，F-42 受控实验）：被硬超时强拆的事务，
 * 其体内尚未发出的 asyncSendData 提交（delay 定时器到点后的补发命令）若落口，会与新
 * 事务的收发交错——写侧在排空/恢复窗被显式拒绝，响应侧由排空丢弃，两半合起来才是
 * 完整的残留防线。排空静默确认后写路径自动放行（对应旧「新事务授予新代后闸门放行」
 * 语义位）；正常收场不排空，后续写零拒绝窗。
 *
 * <p>确定性手段：定时全经 {@link FakeSerialTimers} 手动驱动（排空静默计时器按延迟值
 * 精确点火），零真实时钟等待；事件面用执行者屏障 {@code awaitEventLoopForTest} 确认
 * 已发生。端口不经 registerSource（注册会触发真实 getCommPort 覆盖 mock），对话直接
 * 提交到端口执行者。
 *
 * @author coffee
 */
public class SerialResidualWriteGuardTest {

    private static final String PORT = "/dev/ttyRESIDUAL";
    /**
     * 注入的设备超时（毫秒）：排空静默窗 = 设备超时生效值，本值同时是静默计时器在
     * FakeSerialTimers 里的识别延迟（SerialInfo 构造即注入，无独立覆盖缝）。
     */
    private static final int TEST_DRAIN_SILENCE_MS = 50;

    private SerialSourcePort port;
    private SerialPort serialPort;
    private FakeSerialTimers timers;
    private final AtomicInteger writeCount = new AtomicInteger();

    @Before
    public void setUp() {
        timers = new FakeSerialTimers();
        serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            writeCount.incrementAndGet();
            return inv.<byte[]>getArgument(0).length;
        });
        port = new SerialSourcePort(new SerialInfo(PORT, 9600, 8, 1, 0, 0, TEST_DRAIN_SILENCE_MS), 3, null);
        port.serialPort = serialPort;
    }

    @After
    public void tearDown() {
        timers.close();
    }

    /**
     * 【残留写拒绝】对话被强拆（markTransactionAborted → 异常收场 + 排空）后，被掐对话
     * 的残留补发写请求在排空窗内到达 → SerialWriteException 显式拒绝，不落口。
     */
    @Test
    public void writeRejectedDuringDrainWindow() throws Exception {
        CompletableFuture<Boolean> conv = submitHangingConversation();
        awaitEventLoop();

        port.markTransactionAborted();
        awaitEventLoop();

        try {
            port.asyncSendData(new byte[]{0x01}).get(2, TimeUnit.SECONDS);
            fail("排空窗内的残留写必须被拒绝");
        } catch (ExecutionException e) {
            assertTrue("拒绝应为 SerialWriteException，实际: " + e.getCause(),
                    e.getCause() instanceof SerialWriteException);
        }
        assertTrue("对话 future 应已被强拆异常完成", conv.isCompletedExceptionally());
        assertEquals("拒绝窗内的写不得落口", 0, writeCount.get());
    }

    /**
     * 【放行】排空静默确认（计时器手动到拍）后口回到 IDLE，写路径自动放行。
     */
    @Test
    public void writeAllowedAfterDrainCompletes() throws Exception {
        submitHangingConversation();
        awaitEventLoop();
        port.markTransactionAborted();
        awaitEventLoop();

        fireDrainSilenceTimer();

        port.asyncSendData(new byte[]{0x02}).get(2, TimeUnit.SECONDS);
        awaitEventLoop();
        assertEquals("排空结束后写应真实落口", 1, writeCount.get());
    }

    /**
     * 【正常收场不排空】对话正常完成（无强拆）→ 不进排空 → 后续写立即可落口，
     * 无拒绝窗（正常应答不应付出任何防线路由代价）。
     */
    @Test
    public void normalCompletionKeepsWriteAllowedWithoutDrain() throws Exception {
        CompletableFuture<Boolean> conv = port.submitTransaction(null, null,
                SerialPortExecutor.Priority.HIGH, 1000L, 60_000L,
                src -> CompletableFuture.completedFuture(Boolean.TRUE));
        assertNotNull(conv);
        assertEquals(Boolean.TRUE, conv.get(2, TimeUnit.SECONDS));
        awaitEventLoop();

        port.asyncSendData(new byte[]{0x03}).get(2, TimeUnit.SECONDS);
        awaitEventLoop();
        assertEquals("正常收场后写应直接落口", 1, writeCount.get());
    }

    // ==================== helpers ====================

    /** 提交一个永不完成的对话体（悬挂事务形态），返回对话 future。 */
    private CompletableFuture<Boolean> submitHangingConversation() {
        CompletableFuture<Boolean> conv = port.submitTransaction(null, null,
                SerialPortExecutor.Priority.HIGH, 1000L, 60_000L,
                src -> new CompletableFuture<>());
        assertNotNull(conv);
        return conv;
    }

    /**
     * 点燃排空静默计时器（按延迟值识别：markTransactionAborted 后进入排空挂载的
     * 静默计时，delay = TEST_DRAIN_SILENCE_MS），等执行者处理完对应事件后断言 IDLE。
     */
    private void fireDrainSilenceTimer() throws Exception {
        int silenceIdx = -1;
        for (int i = 0; i < timers.shots.size(); i++) {
            if (timers.shots.get(i).delayMillis == TEST_DRAIN_SILENCE_MS) {
                silenceIdx = i;
            }
        }
        assertTrue("排空静默计时器应已挂载（当前在排空态）", silenceIdx >= 0);
        timers.fire(silenceIdx);
        awaitEventLoop();
        assertEquals("静默确认后口应回到 IDLE", SerialPortExecutor.PortState.IDLE, port.executor().state());
    }

    private void awaitEventLoop() throws Exception {
        port.executor().awaitEventLoopForTest();
    }
}
