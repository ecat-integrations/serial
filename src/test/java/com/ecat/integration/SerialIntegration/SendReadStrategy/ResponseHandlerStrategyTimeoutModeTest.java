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
        Object[] strategies = newDynamicStrategies(source);

        List<Long> delays = invokeAndCaptureDelays(strategies);
        assertEquals(Long.valueOf(2000L), delays.get(0));
        assertEquals(Long.valueOf(2000L), delays.get(1));

        when(source.getTimeout()).thenReturn(50);
        delays = invokeAndCaptureDelays(strategies);
        assertEquals(Long.valueOf(50L), delays.get(0));
        assertEquals(Long.valueOf(50L), delays.get(1));
    }

    @Test
    public void explicitTimeoutMustBeFixed() throws Exception {
        SerialSource source = newSerialSource(2000);
        Object[] strategies = newExplicitStrategies(source, 500L);

        List<Long> delays = invokeAndCaptureDelays(strategies);
        assertEquals(Long.valueOf(500L), delays.get(0));
        assertEquals(Long.valueOf(500L), delays.get(1));

        when(source.getTimeout()).thenReturn(50);
        delays = invokeAndCaptureDelays(strategies);
        assertEquals(Long.valueOf(500L), delays.get(0));
        assertEquals(Long.valueOf(500L), delays.get(1));
    }

    @Test
    public void dynamicTimeoutMustFallBackToReadTimeout() throws Exception {
        assertFallbackForNullSource();

        SerialSource source = newSerialSource(0);
        Object[] strategies = newDynamicStrategies(source);
        List<Long> delays = invokeAndCaptureDelays(strategies);
        assertEquals(Long.valueOf(Const.READ_TIMEOUT_MS.longValue()), delays.get(0));
        assertEquals(Long.valueOf(Const.READ_TIMEOUT_MS.longValue()), delays.get(1));
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

    private static Object[] newDynamicStrategies(SerialSource source) {
        return new Object[] {
            new ByteResponseHandlerStrategy<>(
                    source, context -> true, bytes -> null, exception -> false),
            new DefaultResponseHandlerStrategy<>(
                    source, context -> true, buffer -> null, exception -> false),
        };
    }

    private static Object[] newExplicitStrategies(SerialSource source, long timeoutMs) {
        return new Object[] {
            new ByteResponseHandlerStrategy<>(
                    source, context -> true, bytes -> null, exception -> false, timeoutMs),
            new DefaultResponseHandlerStrategy<>(
                    source, context -> true, buffer -> null, exception -> false, timeoutMs),
        };
    }

    private List<Long> invokeAndCaptureDelays(Object[] strategies) throws Exception {
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
            for (Object strategy : strategies) {
                invokeInterrupt(strategy);
            }
        } finally {
            SerialTimeoutScheduler.unbind();
        }
        assertEquals(strategies.length, delays.size());
        return delays;
    }

    private static void invokeInterrupt(Object strategy) throws Exception {
        Method method = strategy.getClass().getDeclaredMethod(
                "handleResponseInterrupt", interruptContextType(strategy));
        method.setAccessible(true);
        Object context = strategy instanceof ByteResponseHandlerStrategy
                ? new ByteResponseHandlingContext<String>("unused")
                : new ResponseHandlingContext<String>("unused");
        method.invoke(strategy, context);
    }

    private static Class<?> interruptContextType(Object strategy) {
        return strategy instanceof ByteResponseHandlerStrategy
                ? ByteResponseHandlingContext.class
                : ResponseHandlingContext.class;
    }

    private static void assertFallbackForNullSource() throws Exception {
        Object[] strategies = newDynamicStrategies(null);
        for (Object strategy : strategies) {
            Method method = strategy.getClass().getDeclaredMethod("currentTimeoutMs");
            method.setAccessible(true);
            assertEquals(Const.READ_TIMEOUT_MS.longValue(), method.invoke(strategy));
        }
    }
}
