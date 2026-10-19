package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 事务入口的 closed view post-check：lambda 不得执行，key 必须归还，closed/busy 类型分离。
 * 排队竞态的最终硬门由 requester-aware IO 与异步 acquire post-check 单独锁定。
 */
public class SerialTransactionStrategyViewClosedTest {

    private SerialSource sourceWithPort(String portName) {
        SerialSource source = mock(SerialSource.class);
        when(source.getPortName()).thenReturn(portName);
        when(source.getIdentity()).thenReturn("device-a");
        return source;
    }

    @Test
    public void closedSourceRejectsWithoutRunningLambda() throws Exception {
        SerialSource source = sourceWithPort("TX-CLOSED-PORT");
        when(source.isViewClosed()).thenReturn(true);
        when(source.acquire()).thenThrow(new SerialSourceClosedException(
                "acquire", "TX-CLOSED-PORT", "device-a"));

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> {
                    fail("closed source 的事务 lambda 不得执行");
                    return CompletableFuture.completedFuture(true);
                });
        try {
            result.get(1, TimeUnit.SECONDS);
            fail("closed source 事务必须失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
    }

    @Test
    public void sourceClosedAfterAcquireReleasesKeyWithoutRunningLambda() throws Exception {
        SerialSource source = sourceWithPort("TX-RACE-PORT");
        when(source.isViewClosed()).thenReturn(true);
        when(source.acquire()).thenReturn("granted-key");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true));

        try {
            result.get(1, TimeUnit.SECONDS);
            fail("acquire 后 close 的事务必须失败且不执行 lambda");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        verify(source).release("granted-key");
    }

    @Test
    public void pollingDistinguishesClosedAndLockBusy() throws Exception {
        SerialSource closed = sourceWithPort("POLL-CLOSED-PORT");
        when(closed.isViewClosed()).thenReturn(true);
        when(closed.acquirePollingBounded(100)).thenReturn(
                CompletableFuture.completedFuture("granted-key"));
        CompletableFuture<Boolean> closedResult = SerialTransactionStrategy.executePolling(
                closed, 100, src -> CompletableFuture.completedFuture(true), 1000);
        try {
            closedResult.get(1, TimeUnit.SECONDS);
            fail("closed polling 事务必须 lifecycle 失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        verify(closed).release("granted-key");

        SerialSource busy = sourceWithPort("POLL-BUSY-PORT");
        when(busy.acquirePollingBounded(100)).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Boolean> busyResult = SerialTransactionStrategy.executePolling(
                busy, 100, src -> CompletableFuture.completedFuture(true), 1000);
        try {
            busyResult.get(1, TimeUnit.SECONDS);
            fail("lock busy polling 必须失败");
        } catch (Exception expected) {
            assertFalse(SerialSourceClosedException.isSourceClosed(expected.getCause()));
            assertTrue(LockBusySkippedException.isLockBusySkip(expected.getCause()));
        }
    }
}
