package com.ecat.integration.SerialIntegration.Listener;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * BytePooledSerialDataListener 单元测试
 *
 * 覆盖与 {@link PooledSerialDataListenerTest} 同款的分片/完整响应契约（字节版），
 * 以及同一缺陷类的并发回归：池释放链（whenCompleteAsync → release → cleanup）
 * 与在途 onDataReceived 通知的竞态——守卫通过后字段被并发清空，
 * 响应完整分支的 removeDataListener 必须仍按入口快照执行，不得静默跳过。
 *
 * @author coffee
 */
public class BytePooledSerialDataListenerTest {

    private BytePooledSerialDataListener listener;

    @Mock
    private SerialSource mockSerialSource;

    @Mock
    private CompletableFuture<ByteResponseHandlingContext<?>> mockFuture;

    private ByteResponseHandlingContext<String> context;
    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        listener = new BytePooledSerialDataListener();
        context = new ByteResponseHandlingContext<>("test_context");
    }

    @After
    public void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
        listener = null;
        context = null;
    }

    // ==================== 分片/完整响应契约（字节版） ====================

    @Test
    public void testCompleteResponse_ShouldRemoveListener() {
        listener.reset(context, mockFuture, mockSerialSource,
            bytes -> bytes.length > 0 ? bytes : null);

        byte[] data = {0x11, 0x02, 0x0B};
        listener.onDataReceived(data, data.length);

        assertEquals(3, context.getReceiveBytes().length);
        verify(mockFuture, times(1)).complete(context);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    @Test
    public void testIncompleteResponse_ShouldKeepListener() {
        // 长度不满 4 字节的帧视为不完整
        listener.reset(context, mockFuture, mockSerialSource,
            bytes -> bytes.length >= 4 ? bytes : null);

        byte[] partial = {0x11, 0x02};
        listener.onDataReceived(partial, partial.length);

        verify(mockFuture, never()).complete(any());
        verify(mockSerialSource, never()).removeDataListener(listener);

        // 补全后应完成并移除
        byte[] rest = {0x0B, 0x07};
        listener.onDataReceived(rest, rest.length);

        verify(mockFuture, times(1)).complete(context);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    // ==================== 回归：池释放链与在途通知并发 ====================

    /**
     * 字节版同源回归：守卫通过后、响应完整分支的 removeDataListener 之前，
     * 池释放链（ByteResponseHandlerStrategy.handleResponseInterrupt 的
     * whenCompleteAsync → BYTE_POOL.release → cleanup）并发清空 serialSource 字段。
     * 修复前该分支的判空守卫会静默跳过移除（注册残留）；修复后必须按入口快照执行。
     */
    @Test
    public void testCleanupRacingCompleteBranch_ShouldStillRemoveListenerFromEntrySnapshot() throws Exception {
        CountDownLatch checkEntered = new CountDownLatch(1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        CountDownLatch callReturned = new CountDownLatch(1);
        AtomicReference<Throwable> thrownToCaller = new AtomicReference<>();

        listener.reset(context, mockFuture, mockSerialSource, bytes -> {
            checkEntered.countDown();
            try {
                // 卡在守卫之后、complete/remove 分支之前，等待主线程执行 cleanup()
                cleanupFinished.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return bytes; // 响应完整
        });

        Thread notifyingThread = new Thread(() -> {
            try {
                byte[] data = {0x11, 0x02};
                listener.onDataReceived(data, data.length);
            } catch (Throwable t) {
                thrownToCaller.set(t);
            } finally {
                callReturned.countDown();
            }
        }, "simulated-serial-event-thread");
        notifyingThread.start();

        assertTrue("onDataReceived 应已通过守卫并进入 checkResponseFunction",
            checkEntered.await(5, TimeUnit.SECONDS));

        // 并发方：GenericSerialDataListenerPool.release() → cleanup()（生产释放链的原子动作）
        listener.cleanup();
        cleanupFinished.countDown();

        assertTrue("onDataReceived 应正常返回", callReturned.await(5, TimeUnit.SECONDS));
        assertNull("onDataReceived 不得向调用方（notifyListeners）抛异常", thrownToCaller.get());

        verify(mockFuture, times(1)).complete(context);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }
}
