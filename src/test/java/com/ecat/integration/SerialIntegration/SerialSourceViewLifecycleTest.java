package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.fazecast.jSerialComm.SerialPort;

/**
 * {@link SerialSource} terminal owner view 生命周期硬门：close 只终态化本视图；
 * 共享物理口引用计数与同口其他 view 不受影响；closed view 的新 IO/取锁/监听注册全部
 * fail-fast，排队 IO 在真正触碰端口前复查，不产生 zombie write/read。
 */
public class SerialSourceViewLifecycleTest {

    private static final String PORT_NAME = "VIEW-LIFECYCLE-PORT";

    private SerialIntegration integration;
    private SerialSourcePort port;
    private SerialPort physicalPort;
    private SerialSource sourceA;
    private SerialSource sourceB;
    private boolean physicalPortOpen;

    @Before
    public void setUp() {
        integration = new SerialIntegration();
        port = new SerialSourcePort(new SerialInfo(PORT_NAME, 9600, 8, 1, 0), 1, integration);
        physicalPort = mock(SerialPort.class);
        physicalPortOpen = true;
        when(physicalPort.isOpen()).thenAnswer(inv -> physicalPortOpen);
        when(physicalPort.closePort()).thenAnswer(inv -> {
            physicalPortOpen = false;
            return true;
        });
        when(physicalPort.bytesAvailable()).thenReturn(0);
        when(physicalPort.writeBytes(any(byte[].class), anyInt())).thenAnswer(inv ->
                inv.getArgument(0, byte[].class).length);
        port.serialPort = physicalPort;
        integration.serialPortsPutForTest(PORT_NAME, port);
        sourceA = new SerialSource(port, "A");
        sourceB = new SerialSource(port, "B");
    }

    @After
    public void tearDown() {
        if (sourceA != null && sourceA.isViewActive()) {
            sourceA.closePort();
        }
        if (sourceB != null && sourceB.isViewActive()) {
            sourceB.closePort();
        }
        SerialIoPool.resetForTest();
    }

    @Test
    public void closeIsPerViewTerminalAndPreservesSharedPhysicalPort() {
        sourceA.closePort();
        sourceA.closePort();

        assertTrue(sourceA.isViewClosed());
        assertFalse(sourceA.isViewActive());
        assertTrue(sourceB.isViewActive());
        assertTrue(port.isPortOpen());
        assertNotNull(integration.serialPortsGet(PORT_NAME));

        sourceB.closePort();
        assertFalse(port.isPortOpen());
        assertNull(integration.serialPortsGet(PORT_NAME));
    }

    @Test
    public void closedViewRejectsIoWhileActiveViewStillWrites() throws Exception {
        byte[] commandA = {1, 1};
        sourceA.closePort();

        CompletableFuture<Boolean> closedSend = sourceA.asyncSendData(commandA);
        try {
            closedSend.get(1, TimeUnit.SECONDS);
            fail("closed view 的发送必须失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        verify(physicalPort, never()).writeBytes(any(byte[].class), anyInt());

        assertTrue(sourceB.asyncSendData(new byte[] {2, 2}).get(1, TimeUnit.SECONDS));
        verify(physicalPort).writeBytes(new byte[] {2, 2}, 2);
    }

    @Test
    public void closedViewRejectsQueuedWriteBeforePhysicalIo() throws Exception {
        CountDownLatch queueHeadStarted = new CountDownLatch(1);
        CountDownLatch releaseQueueHead = new CountDownLatch(1);
        port.ioExecutor().submit(() -> {
            queueHeadStarted.countDown();
            releaseQueueHead.await();
            return null;
        });
        assertTrue(queueHeadStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<Boolean> queuedSend = sourceA.asyncSendData(new byte[] {9});
        sourceA.closePort();
        releaseQueueHead.countDown();
        try {
            queuedSend.get(1, TimeUnit.SECONDS);
            fail("close 前已入队、task 未执行的写必须被拒绝");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        verify(physicalPort, never()).writeBytes(any(byte[].class), anyInt());
    }

    @Test
    public void closedViewRejectsReadAndListenerRegistration() throws Exception {
        sourceA.closePort();

        CompletableFuture<byte[]> closedRead = sourceA.asyncReadDataBytes();
        try {
            closedRead.get(1, TimeUnit.SECONDS);
            fail("closed view 的读取必须失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }

        try {
            sourceA.addDataListener(new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                }

                @Override
                public void onError(Exception ex) {
                }
            });
            fail("closed view 不得新增 listener");
        } catch (SerialSourceClosedException expected) {
            assertEquals(0, sourceA.getDataListenerCount());
        }

        try {
            sourceA.onFrame((Consumer<byte[]>) frame -> { });
            fail("closed view 不得注册 onFrame");
        } catch (SerialSourceClosedException expected) {
            assertEquals(0, sourceA.getDataListenerCount());
        }

        sourceA.removeAllDataListeners();
    }

    @Test
    public void listenerRegistrationCannotRaceTerminalCleanup() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicReference<Throwable> addFailure = new AtomicReference<>();
        SerialSource closingSource = new SerialSource(port, "closing-listener-view") {
            @Override
            public void removeAllDataListeners() {
                closeEntered.countDown();
                try {
                    assertTrue(releaseClose.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                super.removeAllDataListeners();
            }
        };

        Thread closer = new Thread(closingSource::closePort, "listener-close-race");
        closer.start();
        assertTrue(closeEntered.await(1, TimeUnit.SECONDS));

        Thread adder = new Thread(() -> {
            try {
                closingSource.addDataListener(new SerialDataListener() {
                    @Override
                    public void onDataReceived(byte[] data, int length) {
                    }

                    @Override
                    public void onError(Exception ex) {
                    }
                });
            } catch (Throwable ex) {
                addFailure.set(ex);
            }
        }, "listener-add-race");
        adder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (adder.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, adder.getState());

        releaseClose.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(1));
        adder.join(TimeUnit.SECONDS.toMillis(1));

        assertTrue(SerialSourceClosedException.isSourceClosed(addFailure.get()));
        assertEquals("close 清理后不得残留迟到 listener", 0, closingSource.getDataListenerCount());
    }

    @Test
    public void lateListenerRegistrationIsLinearizedBeforeTerminalCleanup() throws Exception {
        SerialSource source = new SerialSource(port, "listener-registration-race");
        source.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
            }

            @Override
            public void onError(Exception ex) {
            }
        });

        CountDownLatch containsEntered = new CountDownLatch(1);
        CountDownLatch releaseContains = new CountDownLatch(1);
        AtomicReference<Throwable> addFailure = new AtomicReference<>();
        SerialDataListener lateListener = new SerialDataListener() {
            @Override
            public boolean equals(Object other) {
                containsEntered.countDown();
                try {
                    assertTrue(releaseContains.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return super.equals(other);
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(this);
            }

            @Override
            public void onDataReceived(byte[] data, int length) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };

        Thread adder = new Thread(() -> {
            try {
                source.addDataListener(lateListener);
            } catch (Throwable ex) {
                addFailure.set(ex);
            }
        }, "late-listener-add");
        adder.start();
        assertTrue(containsEntered.await(1, TimeUnit.SECONDS));

        Thread closer = new Thread(source::closePort, "late-listener-close");
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (closer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals("close 必须等 listener 注册临界区完成", Thread.State.BLOCKED, closer.getState());
        assertFalse("注册未线性化完成前，close 不得先置 terminal", source.isViewClosed());

        releaseContains.countDown();
        adder.join(TimeUnit.SECONDS.toMillis(1));
        closer.join(TimeUnit.SECONDS.toMillis(1));

        assertNull("注册先于 close 线性化，调用本身应成功", addFailure.get());
        assertTrue(source.isViewClosed());
        assertEquals("close 清理必须移除完整注册临界区内的 listener", 0, source.getDataListenerCount());
    }

    @Test
    public void closedViewAcquireFailsFastWithoutBlockingActiveView() {
        sourceA.closePort();
        assertClosedAcquire(() -> sourceA.acquire());
        assertClosedAcquire(() -> sourceA.acquire(1, TimeUnit.MILLISECONDS));

        String bKey = sourceB.acquire();
        assertNotNull("活跃同口 view 仍可取锁", bKey);
        assertTrue(port.release(bKey));
    }

    @Test
    public void waitingAcquireAfterCloseReleasesGrantedKeyAndThrowsClosed() throws Exception {
        String bKey = sourceB.acquire();
        assertNotNull(bKey);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            acquired.countDown();
            try {
                sourceA.acquire(2, TimeUnit.SECONDS);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "closed-view-acquire-waiter");
        waiter.start();
        assertTrue(acquired.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (port.getWaitingCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, port.getWaitingCount());

        sourceA.closePort();
        assertTrue(port.release(bKey));
        waiter.join(TimeUnit.SECONDS.toMillis(2));
        assertTrue(SerialSourceClosedException.isSourceClosed(failure.get()));

        String nextKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("closed 等待者不得吞锁", nextKey);
        assertTrue(port.release(nextKey));
    }

    @Test
    public void pollingAcquireDistinguishesClosedFromLockBusy() throws Exception {
        String bKey = sourceB.acquire();
        assertNotNull(bKey);
        AtomicReference<CompletableFuture<String>> waitingRef =
                new AtomicReference<>(new CompletableFuture<>());
        Thread poller = new Thread(() -> waitingRef.set(
                sourceA.acquirePollingBounded(100)),
                "closed-view-polling-acquirer");
        poller.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (port.getWaitingCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        sourceA.closePort();
        assertTrue(port.release(bKey));
        poller.join(TimeUnit.SECONDS.toMillis(1));

        try {
            waitingRef.get().get(10, TimeUnit.MILLISECONDS);
            fail("等待返回后 view 已 close 必须抛 lifecycle closed");
        } catch (Exception expected) {
            assertTrue("实际失败异常: " + expected,
                    SerialSourceClosedException.isSourceClosed(expected.getCause() != null
                            ? expected.getCause() : expected));
            assertFalse(expected.getCause() instanceof LockBusySkippedException);
        }

        String nextKey = port.acquire(1, TimeUnit.SECONDS);
        assertNotNull("lifecycle 失败路径必须先归还 key", nextKey);
        assertTrue(port.release(nextKey));
    }

    private void assertClosedAcquire(java.util.concurrent.Callable<String> action) {
        try {
            String key = action.call();
            fail("closed view 取锁不得成功，实际 key=" + key);
        } catch (SerialSourceClosedException expected) {
            // lifecycle hard fence
        } catch (Exception unexpected) {
            fail("closed view 应抛 SerialSourceClosedException，实际: " + unexpected);
        }
    }
}
