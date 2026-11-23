package com.ecat.integration.SerialIntegration.SendReadStrategy;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.integration.SerialIntegration.Const;
import com.ecat.integration.SerialIntegration.SerialSource;

/**
 * 4 参构造动态跟随 source 超时、5 参构造显式固化、动态兜底语义的回归。
 */
public class ResponseHandlerStrategyTimeoutModeTest {

    @Test
    public void dynamicTimeoutMustFollowSerialSourceCurrentValue() throws Exception {
        SerialSource source = newSerialSource(2000);
        ByteResponseHandlerStrategy<String> strategy = newDynamicStrategy(source);

        List<Long> delays = invokeAndCaptureDelays(strategy);
        assertEquals(Long.valueOf(2000L), delays.get(0));

        when(source.getTimeout()).thenReturn(50);
        delays = invokeAndCaptureDelays(strategy);
        assertEquals(Long.valueOf(50L), delays.get(0));
    }

    @Test
    public void explicitTimeoutMustBeFixed() throws Exception {
        SerialSource source = newSerialSource(2000);
        ByteResponseHandlerStrategy<String> strategy = newExplicitStrategy(source, 500L);

        List<Long> delays = invokeAndCaptureDelays(strategy);
        assertEquals(Long.valueOf(500L), delays.get(0));

        when(source.getTimeout()).thenReturn(50);
        delays = invokeAndCaptureDelays(strategy);
        assertEquals(Long.valueOf(500L), delays.get(0));
    }

    @Test
    public void dynamicTimeoutMustFallBackToReadTimeout() throws Exception {
        assertFallbackForNullSource();

        SerialSource source = newSerialSource(0);
        ByteResponseHandlerStrategy<String> strategy = newDynamicStrategy(source);
        List<Long> delays = invokeAndCaptureDelays(strategy);
        assertEquals(Long.valueOf(Const.READ_TIMEOUT_MS.longValue()), delays.get(0));
    }

    private static SerialSource newSerialSource(int timeout) {
        SerialSource source = mock(SerialSource.class);
        when(source.getPortName()).thenReturn("timeout-mode-port");
        when(source.getTimeout()).thenReturn(timeout);
        ExecutorService directExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(directExecutor).execute(any(Runnable.class));
        when(source.getIoExecutor()).thenReturn(directExecutor);
        return source;
    }

    private static ByteResponseHandlerStrategy<String> newDynamicStrategy(SerialSource source) {
        return new ByteResponseHandlerStrategy<>(
                source, context -> true, bytes -> null, exception -> false);
    }

    private static ByteResponseHandlerStrategy<String> newExplicitStrategy(SerialSource source, long timeoutMs) {
        return new ByteResponseHandlerStrategy<>(
                source, context -> true, bytes -> null, exception -> false, timeoutMs);
    }

    private List<Long> invokeAndCaptureDelays(ByteResponseHandlerStrategy<String> strategy) throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> timeoutTask = mock(ScheduledFuture.class);
        List<Long> delays = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    delays.add(invocation.getArgument(1));
                    return timeoutTask;
                });
        SerialTimeoutScheduler.bind(scheduler);
        try {
            invokeInterrupt(strategy);
        } finally {
            SerialTimeoutScheduler.unbind();
        }
        assertEquals(1, delays.size());
        return delays;
    }

    private static void invokeInterrupt(ByteResponseHandlerStrategy<String> strategy) throws Exception {
        Method method = strategy.getClass().getDeclaredMethod(
                "handleResponseInterrupt", ByteResponseHandlingContext.class);
        method.setAccessible(true);
        method.invoke(strategy, new ByteResponseHandlingContext<String>("unused"));
    }

    private static void assertFallbackForNullSource() throws Exception {
        ByteResponseHandlerStrategy<String> strategy = newDynamicStrategy(null);
        Method method = strategy.getClass().getDeclaredMethod("currentTimeoutMs");
        method.setAccessible(true);
        assertEquals(Const.READ_TIMEOUT_MS.longValue(), method.invoke(strategy));
    }
}
