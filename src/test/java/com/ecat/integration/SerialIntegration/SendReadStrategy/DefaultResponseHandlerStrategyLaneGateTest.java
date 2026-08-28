/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.SerialIntegration.SendReadStrategy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.Listener.PooledSerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListenerPool;
import com.ecat.integration.SerialIntegration.SerialSource;

/**
 * 响应超时路径的车道门控回归（bug-record-20260826-123000 / F-47 串口层根因）。
 *
 * <p>缺陷形态：{@code handleResponseInterrupt} 的返回 future 是
 * {@code whenCompleteAsync(清理, serialSource.getIoExecutor())} 的产物——清理动作投递到
 * 端口车道（serial-io:{port}），返回 future 的完成被门控在清理执行之后。当等待方恰是本口
 * 车道 worker（core 写闸 WRITE-INLINE 形态：ioBody 在车道上 join 策略返回的 future）时，
 * 清理任务排在 park 的 worker 之后永不上道：12s 响应超时已异常完成 responseFuture，
 * 但返回 future 永不完成 → join 永等 → 车道僵尸（ttyUSB161 24min+ 活体实证，超时已触发
 * 而许可未释放的日志差分坐实）。
 *
 * <p>契约：返回 future 的完成只依赖 responseFuture（响应/超时），清理必须 fire-and-forget
 * ——不得门控在端口车道执行域上。
 *
 * <p>验证手法：反射直调私有 handleResponseInterrupt（绕过 detectLegacyModeRequired 的
 * JUnit 栈探测——surefire 下 handleResponse 必走 legacy 分支测不到中断路径）；
 * ioExecutor 用单线程执行器并预 park（模拟被 join 等待方占住的端口车道）；
 * SerialTimeoutScheduler 绑定本地 STPE（确定性超时源）。等待用 isDone 轮询到事件发生，禁 sleep。
 */
public class DefaultResponseHandlerStrategyLaneGateTest {

    private static final int AWAIT_SECONDS = 10;

    private ScheduledExecutorService timeoutScheduler;
    private ExecutorService parkedLaneExecutor;
    private CountDownLatch laneRelease;
    private Thread laneParker;

    @Before
    public void setUp() throws Exception {
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        SerialTimeoutScheduler.bind(timeoutScheduler);
        // 单线程车道执行域：先占住唯一线程（模拟端口车道 worker 正 park 在 join 上）
        parkedLaneExecutor = Executors.newSingleThreadExecutor();
        laneRelease = new CountDownLatch(1);
        laneParker = new Thread(() -> {
            try {
                laneRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "parked-lane-worker");
        laneParker.start();
        parkedLaneExecutor.submit(() -> {
            try {
                laneRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        SerialTimeoutScheduler.unbind();
        timeoutScheduler.shutdownNow();
        laneRelease.countDown();
        laneParker.interrupt();
        parkedLaneExecutor.shutdownNow();
    }

    private SerialSource newSerialSourceWithParkedLane() {
        SerialSource source = mock(SerialSource.class);
        when(source.getPortName()).thenReturn("ttyUT161");
        when(source.getIoExecutor()).thenReturn(parkedLaneExecutor);
        when(source.getTimeout()).thenReturn(50);
        doAnswer(inv -> null).when(source).addDataListener(any());
        doAnswer(inv -> null).when(source).removeDataListener(any());
        return source;
    }

    /** 等待 future 完成（事件已发生验证，非定时猜测）。 */
    private static void awaitDone(CompletableFuture<?> future, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(what + " 应在限期内完成（车道被占住时也必须完成——超时已发生）", future.isDone());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void timeoutCompletionMustNotBeGatedByParkedPortLane() throws Exception {
        SerialSource source = newSerialSourceWithParkedLane();
        // checkResponse 恒 null（响应永不完整）→ 唯一完成源 = 50ms 响应超时
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                source, ctx -> true, buffer -> null, ex -> false, 50L);

        Method interrupt = DefaultResponseHandlerStrategy.class
                .getDeclaredMethod("handleResponseInterrupt", ResponseHandlingContext.class);
        interrupt.setAccessible(true);
        CompletableFuture<Boolean> result = (CompletableFuture<Boolean>) interrupt
                .invoke(strategy, new ResponseHandlingContext<>("C ZERO"));

        awaitDone(result, "策略返回 future");
        // 超时路径：exceptionally(handleException: false) → 正常完成值 false（非异常完成）
        assertFalse("超时经 handleException 归一为 false", result.isCompletedExceptionally());
        try {
            assertFalse(result.getNow(null));
        } catch (Exception impossible) {
            throw new AssertionError("已断言非异常完成", impossible);
        }
    }

    /**
     * 位点 2（池满降级临时监听器路径）同型门控回归：返回 future 的完成同样不得被端口车道
     * 上的清理动作门控。
     *
     * <p>手法：先抽干字符串监听器池（acquire 到 null 为止，finally 释放），迫使
     * handleResponseInterrupt 降级走 handleResponseInterruptWithTempListener；其余同池化
     * 路径用例（预 park 车道 + 50ms 响应超时为唯一完成源）。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void tempListenerTimeoutMustNotBeGatedByParkedPortLane() throws Exception {
        SerialSource source = newSerialSourceWithParkedLane();
        List<PooledSerialDataListener> drained = new ArrayList<>();
        try {
            for (PooledSerialDataListener acquired = SerialDataListenerPool.acquire();
                    acquired != null; acquired = SerialDataListenerPool.acquire()) {
                drained.add(acquired);
            }
            // checkResponse 恒 null（响应永不完整）→ 唯一完成源 = 50ms 响应超时
            DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                    source, ctx -> true, buffer -> null, ex -> false, 50L);

            Method interrupt = DefaultResponseHandlerStrategy.class
                    .getDeclaredMethod("handleResponseInterrupt", ResponseHandlingContext.class);
            interrupt.setAccessible(true);
            CompletableFuture<Boolean> result = (CompletableFuture<Boolean>) interrupt
                    .invoke(strategy, new ResponseHandlingContext<>("C ZERO"));

            awaitDone(result, "临时监听器路径策略返回 future");
            assertFalse("超时经 handleException 归一为 false", result.isCompletedExceptionally());
            try {
                assertFalse(result.getNow(null));
            } catch (Exception impossible) {
                throw new AssertionError("已断言非异常完成", impossible);
            }
        } finally {
            drained.forEach(SerialDataListenerPool::release);
        }
    }
}
