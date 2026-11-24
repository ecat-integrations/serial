package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * 排空静默窗绑定设备超时契约（bugs/bug-record-20261124-205000 债 1）：异常收场后的
 * DRAINING 静默窗 = 本口 SerialInfo.timeout 生效值（Const.READ_TIMEOUT_MS 同源缺省），
 * 不再是无依据常数（原 150ms + 系统属性已退役）。依据：对话因慢过设备超时才异常收场，
 * 迟到应答最迟在下一个设备超时窗内到达——静默窗取设备超时必覆盖全部迟到尾巴；
 * 排空总时长上限（该对话事务超时）保持为流式回灌的强制收场安全网。
 *
 * <p>确定性手段：设备超时经 SerialInfo 构造注入（测试控窗缝），定时全经
 * {@link FakeSerialTimers} 按延迟值精确识别与点火，零真实时钟等待；事件面用执行者
 * 屏障 {@code awaitEventLoopForTest} 确认已发生。端口不经 registerSource（注册会触发
 * 真实 getCommPort 覆盖 mock），对话直接提交到端口执行者。
 *
 * @author coffee
 */
public class SerialDrainSilenceTimeoutBindingTest {

    private static final String PORT = "/dev/ttyDRAINSILENCE";
    /** 注入的设备超时（毫秒）：选值避开夹具其他计时延迟（等待 1000 / 上限 60_000），识别无歧义。 */
    private static final int DEVICE_TIMEOUT_MS = 120;

    private SerialSourcePort port;
    private FakeSerialTimers timers;

    @Before
    public void setUp() {
        timers = new FakeSerialTimers();
    }

    @After
    public void tearDown() {
        timers.close();
    }

    /**
     * 【静默窗=注入 timeout】timeout=X 的 source 异常收场进 DRAINING：静默计时器必须以
     * 恰为 X 的延迟挂载（原 150ms 常数的替代判据），静默确认前不开下一场。
     */
    @Test
    public void drainSilenceTimerScheduledWithInjectedDeviceTimeout() throws Exception {
        newPort(DEVICE_TIMEOUT_MS);
        submitHangingConversation();
        awaitEventLoop();
        port.markTransactionAborted();
        awaitEventLoop();

        assertEquals("异常收场后应处于 DRAINING",
                SerialPortExecutor.PortState.DRAINING, port.executor().state());
        assertEquals("静默计时器应恰以注入的设备超时挂载", 1, silenceShots().size());

        timers.fire(lastSilenceShotIndex(DEVICE_TIMEOUT_MS));
        awaitEventLoop();
        assertEquals("静默确认后口应回到 IDLE", SerialPortExecutor.PortState.IDLE, port.executor().state());
    }

    /**
     * 【下一场在静默窗后才开】timeout=X：DRAINING 期间排队的新对话不得开始；静默确认
     * （X 静默期满）后开下一场——「DRAINING 持续 ≥X 后才开下一场」的结构化表达。
     */
    @Test
    public void nextConversationStartsOnlyAfterSilenceConfirmed() throws Exception {
        newPort(DEVICE_TIMEOUT_MS);
        submitHangingConversation();
        awaitEventLoop();
        port.markTransactionAborted();
        awaitEventLoop();

        CompletableFuture<Boolean> next = port.submitTransaction(null, null,
                SerialPriority.HIGH, 1000L, 60_000L,
                src -> CompletableFuture.completedFuture(Boolean.TRUE));
        awaitEventLoop();
        assertEquals("静默确认前口不得离开 DRAINING",
                SerialPortExecutor.PortState.DRAINING, port.executor().state());
        assertFalse("静默确认前下一场对话不得开始", next.isDone());

        timers.fire(lastSilenceShotIndex(DEVICE_TIMEOUT_MS));
        assertEquals("静默确认后下一场对话应完成", Boolean.TRUE, next.get(2, TimeUnit.SECONDS));
        awaitIdle();
        assertEquals("下一场收场后口应回到 IDLE", SerialPortExecutor.PortState.IDLE, port.executor().state());
    }

    /**
     * 【迟到帧丢弃 + 静默重挂】X 内到达的迟到帧被丢弃（不入应用层缓冲），且到达字节
     * 重置静默计时（重挂一条同延迟静默计时器）——「迟到尾巴必在窗内被吞」契约。
     */
    @Test
    public void lateFrameWithinSilenceWindowIsDiscardedAndSilenceRearmed() throws Exception {
        newPort(DEVICE_TIMEOUT_MS);
        submitHangingConversation();
        awaitEventLoop();
        port.markTransactionAborted();
        awaitEventLoop();
        assertEquals(1, silenceShots().size());

        port.executor().submitBytes(new byte[]{0x11, 0x22, 0x33}, 3);
        awaitEventLoop();

        assertEquals("迟到帧不得进入应用层缓冲", 0, port.readAndClearReceiveBuffer().length);
        assertEquals("到达字节应重置静默计时（重挂一条同延迟计时器）", 2, silenceShots().size());

        timers.fire(lastSilenceShotIndex(DEVICE_TIMEOUT_MS));
        awaitEventLoop();
        assertEquals("静默确认后口应回到 IDLE", SerialPortExecutor.PortState.IDLE, port.executor().state());
        assertEquals("排空窗内迟到帧终身丢弃", 0, port.readAndClearReceiveBuffer().length);
    }

    /**
     * 【缺省同源】设备超时非正（0/未配置）时静默窗落 Const.READ_TIMEOUT_MS 缺省——
     * 与事务超时派生、OS 读超时的生效口径同源，不引入第二缺省常数。
     */
    @Test
    public void nonPositiveDeviceTimeoutFallsBackToReadTimeoutMs() throws Exception {
        newPort(0);
        submitHangingConversation();
        awaitEventLoop();
        port.markTransactionAborted();
        awaitEventLoop();

        assertEquals("非正设备超时应以 Const.READ_TIMEOUT_MS 挂静默计时器",
                1, timers.shots.stream()
                        .filter(shot -> shot.delayMillis == Const.READ_TIMEOUT_MS)
                        .collect(Collectors.toList()).size());

        timers.fire(lastSilenceShotIndex(Const.READ_TIMEOUT_MS));
        awaitEventLoop();
        assertEquals(SerialPortExecutor.PortState.IDLE, port.executor().state());
    }

    // ==================== helpers ====================

    /** 建测试口：设备超时经 SerialInfo 注入（排空静默窗唯一控窗缝）。 */
    private void newPort(int deviceTimeoutMs) {
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(
                inv -> inv.<byte[]>getArgument(0).length);
        port = new SerialSourcePort(new SerialInfo(PORT, 9600, 8, 1, 0, 0, deviceTimeoutMs), 3, null);
        port.serialPort = serialPort;
    }

    /** 提交一个永不完成的对话体（悬挂事务形态，waitMs/execTimeout 不落在静默识别区）。 */
    private void submitHangingConversation() {
        CompletableFuture<Boolean> conv = port.submitTransaction(null, null,
                SerialPriority.HIGH, 1000L, 60_000L,
                src -> new CompletableFuture<>());
        assertNotNull(conv);
    }

    /** 排空静默计时器识别：延迟恰为注入设备超时的提交记录。 */
    private java.util.List<FakeSerialTimers.Shot> silenceShots() {
        return timers.shots.stream()
                .filter(shot -> shot.delayMillis == DEVICE_TIMEOUT_MS)
                .collect(Collectors.toList());
    }

    /** 指定延迟的最近一条静默计时器（重挂后取新挂载条目点火）。 */
    private int lastSilenceShotIndex(long expectedDelayMs) {
        int idx = -1;
        for (int i = 0; i < timers.shots.size(); i++) {
            if (timers.shots.get(i).delayMillis == expectedDelayMs) {
                idx = i;
            }
        }
        assertTrue("排空静默计时器应已挂载（延迟 " + expectedDelayMs + "ms，当前在排空态）", idx >= 0);
        return idx;
    }

    private void awaitEventLoop() throws Exception {
        port.executor().awaitEventLoopForTest();
    }

    /**
     * 屏障驱动的 IDLE 等待（事件已发生断言，非 sleep 猜测）：下一场 body 的
     * {@code result.complete} 与 {@code BodyDone} 入队是同线程相邻两步，一次性屏障
     * 可能落在两步之间——反复屏障直到状态到达，2s 有界防挂死。
     */
    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (port.executor().state() != SerialPortExecutor.PortState.IDLE) {
            awaitEventLoop();
            if (System.nanoTime() >= deadline) {
                assertEquals("口应在下一场收场后回到 IDLE",
                        SerialPortExecutor.PortState.IDLE, port.executor().state());
            }
        }
    }
}
