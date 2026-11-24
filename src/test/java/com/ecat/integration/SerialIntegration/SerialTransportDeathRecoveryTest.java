package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * 传输死亡 → 执行者收场接线测试（推模型 RX 验收：SEMIBLOCKING 下传输死亡两形态 →
 * 死亡通知恰一次 → 在飞对话异常收场进排空——与看门狗超时强拆同一条 RECOVERING+排空
 * 路径，收场竞速幂等）。
 *
 * <p>接线：{@code JSerialCommTransport} 死亡通知 → {@code SerialSourcePort} 构造期注册
 * 的 handler → {@code executor.submitAbort("transport-dead: ...")}。IDLE 期死亡无在飞
 * 对话可强拆（abort no-op，写路径自动重开兜底）；本测试钉在飞对话形态（强拆生效）。
 *
 * <p>mock 口形态（确定性）：读线程阻塞在 mock readBytes，死亡经「读返 -1」注入；
 * 对话体在 IO 工作线程等待一个永不完成的 future（模拟设备无应答），直到传输死亡强拆。
 *
 * @author coffee
 */
public class SerialTransportDeathRecoveryTest {

    private SerialSourcePort port;

    @After
    public void tearDown() {
        if (port != null) {
            port.transport().stopReading();
        }
    }

    /**
     * 读死亡（-1 形态）→ 在飞对话被强拆异常收场 → 排空后口回到 IDLE（重开由写路径按需），
     * 死亡通知恰一次（恰一次断言在缝级契约，此处断言执行者侧收敛）。
     */
    @Test(timeout = 20_000)
    public void transportDeath_abortsInFlightConversation_andSettlesToIdle() throws Exception {
        port = new SerialSourcePort(new SerialInfo("/dev/ttyDEATHTEST", 9600, 8, 1, 0, 0, 500), 3, null);
        SerialPort mockPort = mock(SerialPort.class);
        when(mockPort.isOpen()).thenReturn(true);
        when(mockPort.bytesAvailable()).thenReturn(0);
        // 写成功（对话正常起场）+ 读挂起（模拟阻塞于内核 poll）
        when(mockPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return b.length;
        });
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch deathReleased = new CountDownLatch(1);
        when(mockPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            readEntered.countDown();
            deathReleased.await(15, TimeUnit.SECONDS);
            return -1; // 传输死亡形态（PTY 对端消失/口已关实测）
        });
        port.serialPort = mockPort;

        // 挂生产 sink（openPortNow 测试模式不挂——手动接线等价生产形态）
        port.transport().startReading(port::handleIncomingData);
        assertTrue("读线程必须已进入阻塞读", readEntered.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.CompletableFuture<Boolean> deadTransaction =
                SerialTransactionStrategy.executeWithLambda(new SerialSource(port, "death-probe"), src -> {
                    java.util.concurrent.CompletableFuture<Boolean> never = new java.util.concurrent.CompletableFuture<>();
                    src.asyncSendData(new byte[]{0x01});
                    return never; // 模拟设备无应答，等传输死亡/硬超时收场
                }, 10_000);
        deadTransaction.whenComplete((res, ex) -> {
            if (ex != null) {
                failure.set(ex);
            }
        });

        // 传输死亡注入：阻塞读返 -1 → 死亡通知 → submitAbort 强拆在飞对话
        deathReleased.countDown();

        try {
            deadTransaction.get(10, TimeUnit.SECONDS);
            fail("在飞对话必须被传输死亡强拆（异常收场）");
        } catch (java.util.concurrent.ExecutionException expected) {
            // 强拆路径：超时形态异常（abort 以 TimeoutException 完成）或写拒绝
        }
        assertTrue("对话必须异常收场", failure.get() != null);
        // 收场后：排空（默认静默 150ms）→ IDLE
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (port.executor().state() != SerialPortExecutor.PortState.IDLE
                || port.executor().hasInFlightConversation()) {
            if (System.nanoTime() >= deadline) {
                fail("死亡收场后口必须回到 IDLE，实际 " + port.executor().state());
            }
            Thread.sleep(10);
        }
        assertEquals("收场终态", SerialPortExecutor.PortState.IDLE, port.executor().state());
    }
}
