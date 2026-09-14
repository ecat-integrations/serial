package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * F-43 清洗①（bugs/bug-record-20260826-093000 Q1「复活路径」）的 TDD 单测。
 *
 * <p>问题形态（F-42 受控实验实证）：事务硬超时强拆（release + recoverWedgedPort）后，被掐
 * 事务的设备命令链仍活着——读超时被设备 {@code handleException} 吞成 false、独立 delay
 * 定时器到点后，剩余命令仍会写往（可能已重开的）端口，与新事务交错（sim 实证：被掐轮的
 * {@code fpmset} 在强拆 18s 后落 sim，早于下一轮首命令）。
 *
 * <p>修复契约（发送侧残留写闸门，per-port 事务代数）：
 * <ol>
 *   <li>硬超时触发时策略层调用 {@code SerialSource.markTransactionAborted()}：端口把
 *       「最低可接受发送代数」抬到当前代 + 1——被掐事务代内的后续 asyncSendData 提交全部拒绝；</li>
 *   <li>下一次 acquire/acquirePollingBounded 授予新代数后，新事务的发送恢复正常（闸门只拦死事务残留）；</li>
 *   <li>策略层硬超时同时强制异常完成底层 operations future（挂起中的链头确定性终止信号）。</li>
 * </ol>
 *
 * @author coffee
 */
public class SerialResidualWriteGuardTest {

    private SerialSourcePort port;
    private SerialPort serialPort;

    @Before
    public void setUp() {
        serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return b.length;
        });
        port = new SerialSourcePort(new SerialInfo("/dev/ttyTEST0", 9600, 8, 1, 0), 5, null);
        port.serialPort = serialPort;
    }

    /**
     * 【RED：Q1 残留写】持有锁的事务被硬超时强拆（markTransactionAborted）后，
     * 同一代数的后续 asyncSendData 必须被拒绝——修复前写会照发到端口。
     */
    @Test
    public void residualWriteRejected_afterTransactionAborted() throws Exception {
        String key = port.acquire();
        assertNotNull("测试前置：取得锁", key);
        // 合法事务内发送：正常执行
        port.asyncSendData(new byte[]{0x01}).get(5, TimeUnit.SECONDS);
        verify(serialPort, timeout(2000)).writeBytes(any(byte[].class), anyLong());

        // 硬超时强拆：release + markTransactionAborted（策略层顺序）
        port.markTransactionAborted();
        port.release(key);

        // 被掐事务的残留命令（delay 到点后补发）必须被拒绝
        try {
            port.asyncSendData(new byte[]{0x02}).get(5, TimeUnit.SECONDS);
            fail("残留写应被拒绝（异常完成），实际正常完成");
        } catch (ExecutionException e) {
            assertTrue("拒绝原因应为 SerialWriteException，实际: " + e.getCause(),
                    e.getCause() instanceof SerialWriteException);
        }
        // 写口只被合法命令调用过一次（0x01），残留命令 0x02 未落端口
        verify(serialPort, never()).writeBytes(new byte[]{0x02}, 1L);
    }

    /**
     * 闸门不得误伤新事务：强拆后下一次 acquire 授予新代数，新事务发送恢复正常。
     */
    @Test
    public void newTransactionWriteAllowed_afterReacquire() throws Exception {
        String deadKey = port.acquire();
        port.markTransactionAborted();
        port.release(deadKey);

        String freshKey = port.acquire();
        assertNotNull("强拆后端口锁应可重新授予", freshKey);
        port.asyncSendData(new byte[]{0x03}).get(5, TimeUnit.SECONDS);
        verify(serialPort, timeout(2000)).writeBytes(new byte[]{0x03}, 1L);
        port.release(freshKey);
    }

    /**
     * 事务从未中止时（正常运行）发送不受闸门影响：连续两个事务均正常发送。
     */
    @Test
    public void sequentialTransactionsNotAffected_withoutAbort() throws Exception {
        String k1 = port.acquire();
        port.asyncSendData(new byte[]{0x0A}).get(5, TimeUnit.SECONDS);
        port.release(k1);
        String k2 = port.acquire();
        port.asyncSendData(new byte[]{0x0B}).get(5, TimeUnit.SECONDS);
        port.release(k2);
        verify(serialPort, timeout(2000)).writeBytes(new byte[]{0x0A}, 1L);
        verify(serialPort, timeout(2000)).writeBytes(new byte[]{0x0B}, 1L);
    }

    /**
     * 【RED：Q1 链头强拆】硬超时竞速胜出时，底层 operations future 必须被强制异常完成
     * ——被掐事务的挂起链头收到确定性终止信号（修复前 operations 永不完成）。
     */
    @Test
    public void underlyingOperationsForceFailed_onHardTimeout() throws Exception {
        CompletableFuture<Boolean> operations = new CompletableFuture<>();
        CompletableFuture<Boolean> withTimeout = SerialTransactionStrategy.withHardTimeout(operations, 150L);
        try {
            withTimeout.get(3, TimeUnit.SECONDS);
            fail("硬超时后包装 future 应异常完成");
        } catch (ExecutionException e) {
            assertTrue("应为 TimeoutException，实际: " + e.getCause(),
                    e.getCause() instanceof java.util.concurrent.TimeoutException);
        }
        assertTrue("底层 operations future 应已被强制异常完成",
                operations.isCompletedExceptionally());
    }

    /**
     * 策略层契约：事务硬超时触发时调用 {@code SerialSource.markTransactionAborted()}
     * （与 release/recoverWedgedPort 同一恢复链）。
     */
    @Test
    public void strategyMarksTransactionAborted_onHardTimeout() {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-abort");
        SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), 150L);
        verify(source, timeout(2000).times(1)).markTransactionAborted();
    }
}
