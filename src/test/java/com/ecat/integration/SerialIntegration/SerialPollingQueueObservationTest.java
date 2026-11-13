package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.Task.LockBusySkippedException;

/**
 * E3 等价计数观测测试（test-migration-spec-e1-e4 §3.3 契约②的重表达副本，与
 * SerialPollingNonBlockingTest 并存——旧类一律不动，本类锚 C1 新观测缝）。
 *
 * <p>与旧类的断言差异仅在观测锚点：旧 {@code getWaitingCount()} 计数断言改锚
 * {@link SerialSourcePort#getQueueDepth()}，且按 spec §3.4 硬要求与
 * {@link SerialSourcePort#hasInFlight()} <b>成对</b>观测（单独深度存在
 * 「深度==0 但有命令在途」盲区），并以授予-完成计数对（缝③）校验收敛。
 *
 * <p>结构沿用旧类惯用法：真实 SerialSourcePort 锁状态机（构造零 jSerialComm 副作用）
 * + mock SerialSource 桥接，计时/状态断言穿过完整 SerialTransactionStrategy 链路。
 * C1 判据=锁面实现下绿（缝语义自证）；C2 队列化后同一断言组无需改写。
 */
public class SerialPollingQueueObservationTest {

    /** 同源等待容量（与旧类一致，成对迁移）。 */
    private static final int MAX_WAITERS = 3;

    private SerialSourcePort newPort(int maxWaiters) {
        return new SerialSourcePort(new SerialInfo("POLL-QOBS-PORT", 9600, 8, 1, 0), maxWaiters, null);
    }

    /** 桥接到真实端口的 SerialSource mock（旧类同款惯用法）。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.acquirePollingBounded(anyLong())).thenAnswer(inv -> port.acquirePollingBounded(
                null, inv.getArgument(0, Long.class)));
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        return source;
    }

    /** 异线程持锁构造（旧类同款：测试线程自持再取是自死锁形态，守卫抛）。 */
    private String holdLockOnHelperThread(SerialSourcePort port) throws Exception {
        final String[] held = new String[1];
        final CountDownLatch holderDone = new CountDownLatch(1);
        final Throwable[] holderError = new Throwable[1];
        Thread holder = new Thread(() -> {
            try {
                held[0] = port.acquire(10, TimeUnit.SECONDS);
            } catch (Throwable x) {
                holderError[0] = x;
            } finally {
                holderDone.countDown();
            }
        }, "qobs-test-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("持锁辅助线程必须在期限内返回", holderDone.await(10, TimeUnit.SECONDS));
        assertNull("持锁辅助线程不应抛异常", holderError[0]);
        assertNotNull("持锁辅助线程必须取得锁", held[0]);
        return held[0];
    }

    /**
     * 契约②成对观测（旧 lockHeldRoundWaitsInQueueAndCompletesAfterRelease 的新缝版）：
     * 锁忙轮询入队（深度==1 且与在飞==true 成对——在飞是持有者、深度是等待者，两读数
     * 语义互斥无重叠）；释放后轮次完成，深度归零、无在飞、计数对收敛、零弃轮记账。
     */
    @Test
    public void lockHeldRoundObservedByQueueDepthAndInFlightPair() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);
        long grantedBefore = port.getGrantedCount();

        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        assertFalse("锁忙不得立即弃轮（应排队等待）", future.isDone());
        awaitQueueDepth(port, 1);
        assertEquals("锁忙轮询应入队（深度不含在飞）", 1, port.getQueueDepth());
        assertTrue("成对观测：等待者在队的同时持有者在飞", port.hasInFlight());

        assertTrue(port.release(held));
        assertTrue("预算内等到锁：本轮必须完成", future.get(5, TimeUnit.SECONDS));
        assertEquals("完成后队列深度归零（等待者已出队，无残留）", 0, port.getQueueDepth());
        assertFalse("完成后无在飞（事务释放先于外层 future 完成）", port.hasInFlight());
        assertEquals("本轮授予计入授予计数", grantedBefore + 1, port.getGrantedCount());
        assertEquals("计数对收敛（等到的轮次有授予有完成）",
                port.getGrantedCount(), port.getCompletedCount());
        assertEquals("等到锁的轮次不得计入锁忙放弃", 0L, port.getLockBusySkipCount());
    }

    /**
     * 契约②③弃轮形态的新缝版（旧 budgetExhaustedRoundSkipsWithAccounting）：
     * 预算耗尽弃轮后——等待者自摘除（深度归零=无泄漏）、在飞仍是持有者（成对观测
     * 区分「队列空」与「端口闲」）、真弃轮记账 1；未被授予者不进授予计数。
     */
    @Test
    public void budgetExhaustedRoundRestoresDepthAndAccounts() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);
        long grantedBefore = port.getGrantedCount();

        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("持锁不释放：预算耗尽应弃轮");
        } catch (ExecutionException e) {
            assertTrue("预算耗尽弃轮应为 LockBusySkippedException，实际: " + e.getCause(),
                    e.getCause() instanceof LockBusySkippedException);
        }
        awaitQueueDepth(port, 0);
        assertEquals("弃轮等待者必须自摘除（深度归零，无死占位泄漏）", 0, port.getQueueDepth());
        assertTrue("成对观测：队列空但持有者仍在飞（端口并未空闲）", port.hasInFlight());
        assertEquals("真弃轮必须记账（禁静默）", 1L, port.getLockBusySkipCount());
        assertEquals("未被授予者不得计入授予计数", grantedBefore, port.getGrantedCount());
        assertEquals("持有者在飞期间 granted-completed==1（差值恰为那笔在飞授予）",
                1L, port.getGrantedCount() - port.getCompletedCount());

        assertTrue(port.release(held));
        assertEquals("释放后计数对仍收敛", port.getGrantedCount(), port.getCompletedCount());
        assertFalse(port.hasInFlight());
    }

    /** 深度变化事件等待（deadline 轮询：旁池异步入队/摘除后立即读数是竞态，先等事件再断言）。 */
    private static void awaitQueueDepth(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.getQueueDepth() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("队列深度未在 5s 内到达 " + expected
                        + ", actual=" + port.getQueueDepth());
            }
            Thread.yield();
        }
    }
}
