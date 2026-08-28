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

import com.ecat.integration.SerialIntegration.Listener.BytePooledSerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialListenerPools;
import com.ecat.integration.SerialIntegration.SerialSource;

/**
 * 字节族响应策略的车道门控回归（bug-record-20260826-123000 / F-47 串口层根因，
 * 字节族孪生位点）。
 *
 * <p>与 {@link DefaultResponseHandlerStrategyLaneGateTest} 同型缺陷：返回 future 被
 * {@code whenCompleteAsync(清理, 端口车道)} 门控，车道被 join 等待方占住时超时已完成而返回
 * future 永不完成。验证手法与契约同彼处：反射直调 handleResponseInterrupt 绕过 legacy 探测，
 * 预 park 的单线程车道执行域 + 本地绑定超时调度器 + isDone 轮询等待。
 */
public class ByteResponseHandlerStrategyLaneGateTest {

    private static final int AWAIT_SECONDS = 10;

    private ScheduledExecutorService timeoutScheduler;
    private ExecutorService parkedLaneExecutor;
    private CountDownLatch laneRelease;
    private Thread laneParker;

    @Before
    public void setUp() {
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        SerialTimeoutScheduler.bind(timeoutScheduler);
        parkedLaneExecutor = Executors.newSingleThreadExecutor();
        laneRelease = new CountDownLatch(1);
        laneParker = new Thread(() -> {
            try {
                laneRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "parked-lane-worker-byte");
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
    public void tearDown() {
        SerialTimeoutScheduler.unbind();
        timeoutScheduler.shutdownNow();
        laneRelease.countDown();
        laneParker.interrupt();
        parkedLaneExecutor.shutdownNow();
    }

    private SerialSource newSerialSourceWithParkedLane() {
        SerialSource source = mock(SerialSource.class);
        when(source.getPortName()).thenReturn("ttyUT161B");
        when(source.getIoExecutor()).thenReturn(parkedLaneExecutor);
        when(source.getTimeout()).thenReturn(50);
        doAnswer(inv -> null).when(source).addDataListener(any());
        doAnswer(inv -> null).when(source).removeDataListener(any());
        return source;
    }

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
        // checkResponse 恒 null → 唯一完成源 = 50ms 响应超时
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                source, ctx -> true, bytes -> null, ex -> false, 50L);

        Method interrupt = ByteResponseHandlerStrategy.class
                .getDeclaredMethod("handleResponseInterrupt", ByteResponseHandlingContext.class);
        interrupt.setAccessible(true);
        CompletableFuture<Boolean> result = (CompletableFuture<Boolean>) interrupt
                .invoke(strategy, new ByteResponseHandlingContext<>("x"));

        awaitDone(result, "策略返回 future");
        assertFalse("超时经 handleException 归一为 false", result.isCompletedExceptionally());
        assertFalse(result.getNow(null));
    }

    /**
     * 位点 4（字节族池满降级临时监听器路径）同型门控回归：返回 future 的完成同样不得被
     * 端口车道上的清理动作门控。
     *
     * <p>手法：先抽干字节监听器池（acquire 到 null 为止，finally 释放），迫使
     * handleResponseInterrupt 降级走 handleResponseInterruptWithTempListener；其余同池化
     * 路径用例（预 park 车道 + 50ms 响应超时为唯一完成源）。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void tempListenerTimeoutMustNotBeGatedByParkedPortLane() throws Exception {
        SerialSource source = newSerialSourceWithParkedLane();
        List<BytePooledSerialDataListener> drained = new ArrayList<>();
        try {
            for (BytePooledSerialDataListener acquired = SerialListenerPools.BYTE_POOL.acquire();
                    acquired != null; acquired = SerialListenerPools.BYTE_POOL.acquire()) {
                drained.add(acquired);
            }
            // checkResponse 恒 null → 唯一完成源 = 50ms 响应超时
            ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                    source, ctx -> true, bytes -> null, ex -> false, 50L);

            Method interrupt = ByteResponseHandlerStrategy.class
                    .getDeclaredMethod("handleResponseInterrupt", ByteResponseHandlingContext.class);
            interrupt.setAccessible(true);
            CompletableFuture<Boolean> result = (CompletableFuture<Boolean>) interrupt
                    .invoke(strategy, new ByteResponseHandlingContext<>("x"));

            awaitDone(result, "字节临时监听器路径策略返回 future");
            assertFalse("超时经 handleException 归一为 false", result.isCompletedExceptionally());
            assertFalse(result.getNow(null));
        } finally {
            drained.forEach(SerialListenerPools.BYTE_POOL::release);
        }
    }
}
