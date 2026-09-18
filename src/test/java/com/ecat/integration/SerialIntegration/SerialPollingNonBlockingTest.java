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
 * 【20260913-073600 修复，方案 c：轮询并入 FIFO 有界等待】serial 轮询锁契约测试
 * （与 modbus {@code ModbusPollingNonBlockingTest} 同型镜像）。
 *
 * <p>红测形态（修复前）：轮询锁忙立即弃轮（旧零等待弃轮契约）——fixedDelay
 * 相位锁定下共享口输家每周期全败，分钟级持续饥饿无公平上界（modbus C2 fixture 实证
 * 23 分钟零帧；serial 同构风险）。此前为保定时线程零 park 把轮询踢出 FIFO 公平队列，
 * 是该回归根因。
 *
 * <p>契约（修复后）：
 * <ul>
 *   <li>①入口非阻塞：{@code executePolling} 调用线程毫秒级拿到 future，锁忙时的等待
 *       移交 IO 旁池线程——定时线程零 park（调度安全边界保留）；</li>
 *   <li>②抢占 + 有界等待：锁忙时进入既有 FIFO {@code waitQueue} 排队（budget 内等到锁
 *       → 本轮完成事务），与写命令同队同公平；</li>
 *   <li>③真弃轮才记账：预算耗尽/队列满/旁池拒绝才以 {@link LockBusySkippedException}
 *       弃轮并使 {@code getLockBusySkipCount()} 递增——等到锁的轮次不计（观测不说谎）；</li>
 *   <li>④FIFO 公平：同源多个等待者按入队序先后授予（相位锁定饥饿的根治点）；</li>
 *   <li>⑤写命令路径（{@code executeWithLambda}）不回归：锁忙仍按有限等待 park。</li>
 * </ul>
 *
 * <p>测试结构：锁状态机用真实 {@code SerialSourcePort}（构造不触碰 jSerialComm）；
 * 策略层经 mock {@code SerialSource} 把 acquire/acquirePollingBounded/release 桥接到
 * 该真实端口——计时/队列断言穿过完整策略链路，无需真实串口设备。持锁者一律辅助线程
 * （同线程嵌套守卫后「锁忙」用例的标准前置：测试线程自持再取是自死锁形态，守卫抛）。
 * 时间上界断言是「调用线程零 park」的确定性判据；同步一律用 future.get(timeout)/latch/
 * deadline 轮询，无 Thread.sleep 猜测。
 */
public class SerialPollingNonBlockingTest {

    /** 轮询入口的非阻塞上界：远小于等锁 park 时长；留足 CI 慢机余量。 */
    private static final long NON_BLOCKING_BOUND_MS = 2_000;
    /** 同源等待容量（与生产默认一致；并发形态测试按容量界分「等到/弃轮」）。 */
    private static final int MAX_WAITERS = 3;
    /** 写路径注入的短等锁预算（经可配档重载显式传入）：主用例不再烧满默认 5s。 */
    private static final long WRITE_LOCK_WAIT_MS = 300;
    /** 写路径有限等待的 park 下界：证明仍在等锁（注入 300ms 的大部分；
     *  生产默认 5s 的契约面由 {@link #writePathDefaultLockWaitRemainsFiveSeconds()} 常量断言守卫）。 */
    private static final long WRITE_WAIT_MIN_MS = 250;

    /** 真实锁状态机端口（不 openPort，构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort(int maxWaiters) {
        return new SerialSourcePort(new SerialInfo("POLL-NB-PORT", 9600, 8, 1, 0), maxWaiters, null);
    }

    /** 桥接到真实端口的 SerialSource mock：策略链路的计时/状态断言穿过真实锁状态机。 */
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

    /**
     * 异线程持锁构造（同线程嵌套守卫后「锁忙」用例的标准前置）：生产中持锁者恒为别的事务线程
     * （写闸线程/轮询 worker），测试线程自持再取是自死锁形态（守卫 fail-fast）。持锁者改为
     * 辅助线程取得后直接退出（不 release：锁状态在端口对象上，与持锁线程存活无关），
     * 释放由测试线程按 key 执行。
     */
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
        }, "nb-test-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("持锁辅助线程必须在期限内返回", holderDone.await(10, TimeUnit.SECONDS));
        assertNull("持锁辅助线程不应抛异常", holderError[0]);
        assertNotNull("持锁辅助线程必须取得锁", held[0]);
        return held[0];
    }

    /** 契约①②【红→绿主证】：锁忙不立即弃轮——入队有界等待，预算内等到锁则本轮完成。 */
    @Test
    public void lockHeldRoundWaitsInQueueAndCompletesAfterRelease() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("入口必须毫秒级返回（耗时 " + elapsed + "ms），等待在旁池不在调用线程",
                elapsed < NON_BLOCKING_BOUND_MS);
        assertFalse("锁忙不得立即弃轮（应进入 FIFO 有界等待）", future.isDone());
        awaitWaitingCount(port, 1);
        assertEquals("等待者应入等待队列", 1, port.getWaitingCount());

        assertTrue(port.release(held));
        assertTrue("预算内等到锁：本轮必须完成事务（相位锁定饥饿的根治点）",
                future.get(5, TimeUnit.SECONDS));
        assertEquals("等到锁的轮次不得计入锁忙放弃", 0L, port.getLockBusySkipCount());

        String next = port.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("事务完成后锁应已释放", next);
        port.release(next);
    }

    /** 契约③：持锁不释放跨过预算 → 才以 LockBusySkippedException 弃轮且有记账。 */
    @Test
    public void budgetExhaustedRoundSkipsWithAccounting() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);

        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("持锁不释放：预算耗尽应弃轮");
        } catch (ExecutionException e) {
            assertTrue("预算耗尽弃轮应为 LockBusySkippedException，实际: " + e.getCause(),
                    e.getCause() instanceof LockBusySkippedException);
        }
        assertEquals("真弃轮必须记账（禁静默）", 1L, port.getLockBusySkipCount());
        assertTrue(port.release(held));
    }

    /**
     * 契约④FIFO 公平：同源两个等待者按入队序先后授予完成。
     * 完成点在事务体内刻录：release 内联在事务 future 的 whenComplete 链里、先于轮询
     * 外层 future 完成，跨线程的外层 whenComplete 时戳与授予序存在调度竞态（先授予者的
     * 回调可能后执行），只有锁内刻录才与授予序构成 happens-before 密闭的因果链。
     */
    @Test
    public void waitersGrantedInFifoOrder() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);

        long[] doneAt = new long[2];
        CompletableFuture<Boolean> first = SerialTransactionStrategy.executePolling(
                source, src -> {
                    doneAt[0] = System.nanoTime();
                    return CompletableFuture.completedFuture(true);
                });
        // 入队序栅栏：executePolling 的 acquire 在旁池线程入队，提交序≠入队序——
        // 不等 first 入队就提交 second，second 可能先入队，FIFO 按入队序授予会让
        // doneAt[1] 先刻（modbus 同型镜像高并行负载下实证翻车）。先等 first 入队，
        // 入队序才确定为 [first, second]，断言测的才是「按入队序授予」这一契约本身
        awaitWaitingCount(port, 1);
        CompletableFuture<Boolean> second = SerialTransactionStrategy.executePolling(
                source, src -> {
                    doneAt[1] = System.nanoTime();
                    return CompletableFuture.completedFuture(true);
                });
        awaitWaitingCount(port, 2);
        assertEquals("两个等待者都应入队", 2, port.getWaitingCount());

        assertTrue(port.release(held));
        assertTrue("队头等待者应先授予完成", first.get(5, TimeUnit.SECONDS));
        assertTrue("次位等待者随后授予完成", second.get(5, TimeUnit.SECONDS));
        assertTrue("FIFO：先入队者事务体先完成（锁内完成点 doneAt0=" + doneAt[0] + ", doneAt1=" + doneAt[1] + "）",
                doneAt[0] <= doneAt[1]);
        assertEquals(0L, port.getLockBusySkipCount());
    }

    /**
     * 契约②③并发形态：同源 4 轮询竞争（等待容量 3）——容量内 3 个入队等到锁完成，
     * 超容量的 1 个立即弃轮记账（有界队列的背压边界）。
     */
    @Test
    public void concurrentPollersSplitIntoWaitersAndCapacitySkip() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);

        int pollers = MAX_WAITERS + 1;
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] results = new CompletableFuture[pollers];
        for (int i = 0; i < pollers; i++) {
            results[i] = SerialTransactionStrategy.executePolling(
                    source, src -> CompletableFuture.completedFuture(true));
        }
        // 事件已发生才 release：容量内 3 个先入队、超容量 1 个在锁仍被持时被拒（队列满）——
        // release 抢先会让等待者走快路径直接授予，「3 等到 + 1 弃轮」的分治退化成竞态
        awaitWaitingCount(port, MAX_WAITERS);
        awaitLockBusySkipCount(port, 1);

        assertTrue(port.release(held));
        int completed = 0;
        int skipped = 0;
        for (CompletableFuture<Boolean> f : results) {
            try {
                assertTrue(f.get(5, TimeUnit.SECONDS));
                completed++;
            } catch (ExecutionException e) {
                assertTrue("容量外弃轮应为 LockBusySkippedException，实际: " + e.getCause(),
                        e.getCause() instanceof LockBusySkippedException);
                skipped++;
            }
        }
        assertEquals("容量内等待者应全部等到锁完成", MAX_WAITERS, completed);
        assertEquals("超容量 1 个立即弃轮", 1, skipped);
        assertEquals("只有真弃轮计数", 1L, port.getLockBusySkipCount());
    }

    /**
     * 契约⑤写路径不回归：锁忙时 executeWithLambda 仍按有限等待 park 后异常完成——
     * 闸内 IO 体对锁的等待语义保留，有界等待只作用于轮询入口。经等锁可配档重载注入
     * 300ms 短预算缩短验证时长（「等满才异常完成」的语义不变）；生产默认 5s 由下方
     * 零耗时常量断言单独锁死。
     */
    @Test
    public void executeWithLambdaKeepsBoundedWait_whenLockHeld() throws Exception {
        SerialSourcePort port = newPort(MAX_WAITERS);
        SerialSource source = bridgedSource(port);
        String held = holdLockOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true),
                WRITE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("锁忙时 executeWithLambda 应等待超时后异常完成");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("写路径应保留有限等待（park 至少 " + WRITE_WAIT_MIN_MS + "ms，实际 " + elapsed + "ms）",
                elapsed >= WRITE_WAIT_MIN_MS);
        assertTrue(port.release(held));
    }

    /**
     * 契约⑤常量面（零耗时纯断言）：写路径等锁默认预算 = 5s
     * （{@link SerialSourcePort#DEFAULT_ACQUIRE_WAIT_SECONDS}，{@code acquire()} 无参入口取值）。
     * 主用例注入 300ms 短预算后，默认值漂移（如有人改小）不会再被任何用例察觉——
     * 本断言以纯常量锁死生产默认，零等待。
     */
    @Test
    public void writePathDefaultLockWaitRemainsFiveSeconds() {
        assertEquals(5L, SerialSourcePort.DEFAULT_ACQUIRE_WAIT_SECONDS);
    }

    /**
     * 等待者入队事件等待（deadline 轮询验证事件已发生，超时即失败）：锁忙等待经 IO 旁池
     * 异步入队，入口返回后立即读数是竞态——先等「已入队」再断言/再 release。
     */
    private static void awaitWaitingCount(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.getWaitingCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待者未在 5s 内入队: expected>=" + expected
                        + ", actual=" + port.getWaitingCount());
            }
        }
    }

    /** 真弃轮记账事件等待（同上：旁池任务内 acquire 被拒/预算耗尽后才计数，立即读数是竞态）。 */
    private static void awaitLockBusySkipCount(SerialSourcePort port, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.getLockBusySkipCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("弃轮记账未在 5s 内发生: expected>=" + expected
                        + ", actual=" + port.getLockBusySkipCount());
            }
        }
    }
}
