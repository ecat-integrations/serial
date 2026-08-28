package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写路径域池路由契约（bugs/fixed/bug-record-20260826-071900 的结构性根除回归，
 * 29 号 v2 S1：[WRITE-INLINE] 逃逸口退役）。
 *
 * <p><b>071900 自锁形态与结构性根除论证</b>：旧形态下 core 写闸 ioBody 与内层写共享
 * 同一条引擎车道队列（serial-io:{port} 单线程车道）——任务体内的 join 等待排在自身
 * 之后的入队写，队头自锁，只能靠硬超时强拆；当时的修复是「当前线程即车道 worker 则
 * 直发不入队」的逃逸口（SchedulerEngine.currentExecutingLaneKey 检测）。本域自持
 * SerialIoPool 后：<b>写闸任务体（引擎车道/业务线程）与写 IO（域池 per-port 视图）
 * 不再共享任何队列</b>——跨执行域 join 天然完成，需要给自己开逃逸口的机制不复存在，
 * 逃逸口及其检测逻辑已删除。本测试锁定退役后的契约：
 * <ul>
 *   <li>asyncSendData 恒经域池 per-port 视图执行（无「当前线程直发」旁路——写与轮询/
 *       finalize 同口 FIFO 串行契约对所有调用方一律成立）；</li>
 *   <li>写闸 join 形态（外部任务体 join 写 future——071900 调用链的当代表达）跨执行域
 *       完成，零等待自锁不可再现；</li>
 *   <li>同口多写 FIFO 串行（禁乱序并发写同口——RTU 半双工总线本性）。</li>
 * </ul>
 *
 * <p>同步纪律：latch 有界等待，无 Thread.sleep。</p>
 */
public class SerialWriteDomainPoolTest {

    private SerialSourcePort port;
    private final AtomicReference<byte[]> written = new AtomicReference<>();
    private final AtomicInteger writeCount = new AtomicInteger();

    @Before
    public void setUp() {
        port = new SerialSourcePort(new SerialInfo("ttyUSB161-domainwrite", 9600, 8, 1, 0), 1, null);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) {
                written.set((byte[]) invocation.getArgument(0));
                writeCount.incrementAndGet();
                return ((byte[]) invocation.getArgument(0)).length;
            }
        });
        port.serialPort = serialPort;
    }

    @After
    public void tearDown() {
        SerialIoPool.resetForTest();
    }

    /** 写恒经域池执行（ecat-serial-io-N 线程）——逃逸口退役后无「当前线程直发」旁路。 */
    @Test(timeout = 15000)
    public void writeAlwaysRoutesThroughDomainPoolWorker() throws Exception {
        final AtomicReference<String> writerThread = new AtomicReference<>();
        port.serialPort = mockSerialPort(inv -> {
            writerThread.set(Thread.currentThread().getName());
            return ((byte[]) inv.getArgument(0)).length;
        });

        CompletableFuture<Boolean> ok = port.asyncSendData("ROUTE".getBytes(StandardCharsets.US_ASCII));
        assertTrue("写 future 必须成功完成", ok.get(5, TimeUnit.SECONDS));
        assertTrue("写必须执行在域池线程 ecat-serial-io-N（无任何旁路直发），实际: " + writerThread.get(),
                writerThread.get().matches("ecat-serial-io-\\d+"));
    }

    /**
     * 071900 调用链的当代表达：外部任务体（写闸 ioBody 形态——在「非 serial 域池」的
     * 执行域上运行）join asyncSendData 返回的写 future——跨执行域 join 必须完成。
     * 旧形态红：任务体与内层写共享车道队列时 join 永等（3s TimeoutException）；
     * 域池形态绿：写闸线程与写 IO 分属两个队列，写完成即返。
     */
    @Test(timeout = 15000)
    public void writeGateBodyJoiningWriteAcrossExecutionDomainsCompletes() throws Exception {
        final CompletableFuture<Boolean> gateResult = new CompletableFuture<>();
        // 模拟 core 写闸 ioBody：独立执行域（专属单线程执行器=旧车道位置）任务体内 join 内层写
        final java.util.concurrent.ExecutorService gateLane =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        r -> new Thread(r, "gate-lane-standin"));
        try {
            gateLane.execute(() -> {
                try {
                    gateResult.complete(port.asyncSendData("ZERO_START".getBytes(StandardCharsets.US_ASCII)).join());
                } catch (Throwable t) {
                    gateResult.completeExceptionally(t);
                }
            });

            Boolean ok = gateResult.get(3, TimeUnit.SECONDS);
            assertTrue("写 future 应成功完成", ok);
            assertEquals("写载荷应真实送达串口", "ZERO_START",
                    new String(written.get(), StandardCharsets.US_ASCII));
        } finally {
            gateLane.shutdownNow();
        }
    }

    /** 同口多写 FIFO 串行：提交顺序=执行顺序且无并发交叉（165500 承重顺序的写侧面）。 */
    @Test(timeout = 15000)
    public void samePortWritesExecuteInFifoOrderWithoutConcurrency() throws Exception {
        final int writes = 20;
        final List<Integer> order = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        port.serialPort = mockSerialPort(inv -> {
            int now = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            active.decrementAndGet();
            order.add((int) ((byte[]) inv.getArgument(0))[0]);
            return ((byte[]) inv.getArgument(0)).length;
        });

        CompletableFuture<?>[] futures = new CompletableFuture[writes];
        for (int i = 0; i < writes; i++) {
            futures[i] = port.asyncSendData(new byte[]{(byte) i});
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

        assertEquals("全部写必须完成", writes, order.size());
        for (int i = 0; i < writes; i++) {
            assertEquals("同口写执行顺序必须=提交顺序（FIFO，禁乱序并发写同口）",
                    i, order.get(i).intValue());
        }
        assertEquals("同口写不得并发交叉", 1, maxConcurrent.get());
    }

    /** mock 串口：开/可用恒定，writeBytes 委托给定 answer（测试注入观测点）。 */
    private static SerialPort mockSerialPort(Answer<Integer> writeAnswer) {
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(writeAnswer);
        return serialPort;
    }
}
