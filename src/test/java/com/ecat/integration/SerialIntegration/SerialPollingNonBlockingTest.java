package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.Task.LockBusySkippedException;

/**
 * serial 轮询锁契约测试（serial IO 最小模型语义：有界优先级队列承载轮询有界等待）。
 *
 * <p>契约（历史沿革：20260913-073600 方案 c 并入 FIFO 有界等待 → 现由执行者队列承载）：
 * <ul>
 *   <li>①入口非阻塞：{@code executePolling} 调用线程毫秒级拿到 future（入队即返回，
 *       O(1) 小同步块），定时线程零 park（调度安全边界保留）；</li>
 *   <li>②有界等待：口忙时对话在优先级队列排队（budget 内轮到 → 本轮完成事务），
 *       与写命令同队同公平；</li>
 *   <li>③真弃轮才记账：预算耗尽/队满/被挤出才以 {@link LockBusySkippedException}（或
 *       其子类 {@link PortQueueFullException}）异常完成并使 {@code getLockBusySkipCount()}
 *       递增——等到口的轮次不计（观测不说谎）；</li>
 *   <li>④FIFO 公平：同优先级等待者按入队序先后授予；</li>
 *   <li>⑤写命令路径（{@code executeWithLambda}）：队满同步抛 {@link PortQueueFullException}
 *       （§3.5 显式背压）；等锁预算耗尽则 future 以 IllegalStateException 异常完成。</li>
 * </ul>
 *
 * <p>测试结构：真实端口对象（构造不触碰 jSerialComm）；真实 SerialSource 视图挂靠
 * （注册事件对假端口名 open 失败无害），计时/队列断言穿过完整策略链路。口占用者一律
 * 辅助线程经 {@code port.acquire}（HELD 对话）取得后退出，释放由测试线程按 key 执行。
 * 时间上界断言是「入口非阻塞」的确定性判据；同步一律用 future.get(timeout)/队列深度
 * 轮询（判终态），无 Thread.sleep 猜测。
 */
public class SerialPollingNonBlockingTest {

    /** 轮询入口的非阻塞上界：远小于等锁时长；留足 CI 慢机余量。 */
    private static final long NON_BLOCKING_BOUND_MS = 2_000;
    /** 端口对话队列容量（与生产默认一致；容量边界用例按容量界分「等到/弃轮」）。 */
    private static final int QUEUE_CAPACITY = 3;
    /** 写路径注入的短等锁预算（经可配档重载显式传入）：主用例不再烧满默认 5s。 */
    private static final long WRITE_LOCK_WAIT_MS = 300;
    /** 写路径有限等待的下界：证明仍在排队等待（生产默认 5s 由常量断言单独锁死）。 */
    private static final long WRITE_WAIT_MIN_MS = 250;

    /** 真实端口对象（不 openPort，构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort(int capacity) {
        return new SerialSourcePort(new SerialInfo("POLL-NB-PORT", 9600, 8, 1, 0), capacity, null);
    }

    /** 真实视图（挂靠注册对假端口名 open 失败无害；submitTransaction 走完整链路）。 */
    private SerialSource newSource(SerialSourcePort port) {
        return new SerialSource(port, "poll-nb-test");
    }

    /**
     * 异线程占用口构造（「口忙」用例的标准前置）：生产中占用者恒为别的事务线程。
     * HELD 对话由辅助线程取得后线程退出（占用状态在端口对象上，与线程存活无关），
     * 释放由测试线程按 key 执行。
     */
    private String holdPortOnHelperThread(SerialSourcePort port) throws Exception {
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
        }, "nb-test-port-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("占用辅助线程必须在期限内返回", holderDone.await(10, TimeUnit.SECONDS));
        assertNull("占用辅助线程不应抛异常", holderError[0]);
        assertNotNull("占用辅助线程必须取得口", held[0]);
        return held[0];
    }

    /** 契约①②【主证】：入口非阻塞 + 口忙时入队有界等待，释放后本轮完成事务。 */
    @Test
    public void lockBusyRoundWaitsInQueueAndCompletesAfterRelease() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, src -> CompletableFuture.completedFuture(true));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("入口必须毫秒级返回（耗时 " + elapsed + "ms），等待在队列不在调用线程",
                elapsed < NON_BLOCKING_BOUND_MS);
        assertFalse("口忙不得立即弃轮（应入队有界等待）", future.isDone());
        awaitQueued(port, 1);

        assertTrue(port.release(held));
        assertTrue("预算内轮到：本轮必须完成事务", future.get(5, TimeUnit.SECONDS));
        assertEquals("等到口的轮次不得计入锁忙放弃", 0L, port.getLockBusySkipCount());

        String next = port.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("事务完成后口应可再次取得", next);
        port.release(next);
    }

    /** 契约③：口占用跨过预算 → 才以 LockBusySkippedException 弃轮且有记账。 */
    @Test
    public void budgetExhaustedRoundSkipsWithAccounting() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                source, 200L, src -> CompletableFuture.completedFuture(true), 60_000L);
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("口占用不释放：预算耗尽应弃轮");
        } catch (ExecutionException e) {
            assertTrue("预算耗尽弃轮应为 LockBusySkippedException，实际: " + e.getCause(),
                    e.getCause() instanceof LockBusySkippedException);
        }
        assertEquals("真弃轮必须记账（禁静默）", 1L, port.getLockBusySkipCount());
        assertTrue(port.release(held));
    }

    /**
     * 契约④FIFO 公平：同优先级两个等待者按入队序先后授予完成。
     * 完成点在事务体内刻录（doneAt），先等双双入队再释放，入队序才确定为 [first, second]。
     */
    @Test
    public void waitersGrantedInFifoOrder() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        long[] doneAt = new long[2];
        CompletableFuture<Boolean> first = SerialTransactionStrategy.executePolling(
                source, src -> {
                    doneAt[0] = System.nanoTime();
                    return CompletableFuture.completedFuture(true);
                });
        awaitQueued(port, 1);
        CompletableFuture<Boolean> second = SerialTransactionStrategy.executePolling(
                source, src -> {
                    doneAt[1] = System.nanoTime();
                    return CompletableFuture.completedFuture(true);
                });
        awaitQueued(port, 2);

        assertTrue(port.release(held));
        assertTrue("队头等待者应先授予完成", first.get(5, TimeUnit.SECONDS));
        assertTrue("次位等待者随后授予完成", second.get(5, TimeUnit.SECONDS));
        assertTrue("FIFO：先入队者事务体先完成（doneAt0=" + doneAt[0] + ", doneAt1=" + doneAt[1] + "）",
                doneAt[0] <= doneAt[1]);
        assertEquals(0L, port.getLockBusySkipCount());
    }

    /**
     * 契约②③容量边界：口占用时 QUEUE_CAPACITY 个轮询入队等到、超容量的 1 个以
     * {@link PortQueueFullException}（LockBusySkippedException 子类）异常完成并记账
     * （有界队列的背压边界，轮询框架沿继承链内化为跳过）。
     */
    @Test
    public void concurrentPollersSplitIntoWaitersAndCapacitySkip() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        int pollers = QUEUE_CAPACITY + 1;
        CompletableFuture<Boolean>[] results = new CompletableFuture[pollers];
        for (int i = 0; i < pollers; i++) {
            results[i] = SerialTransactionStrategy.executePolling(
                    source, src -> CompletableFuture.completedFuture(true));
        }
        // 事件已发生才 release：容量内先入队、超容量 1 个在口仍被占时被拒（队列满）
        // ——release 抢先会让等待者直接授予，「容量内等到 + 1 弃轮」的分治退化成竞态
        awaitQueued(port, QUEUE_CAPACITY);
        awaitSkipCount(port, 1);

        assertTrue(port.release(held));
        int completed = 0;
        int skipped = 0;
        for (CompletableFuture<Boolean> f : results) {
            try {
                assertTrue(f.get(5, TimeUnit.SECONDS));
                completed++;
            } catch (ExecutionException e) {
                assertTrue("容量外弃轮应为 LockBusySkippedException 子类，实际: " + e.getCause(),
                        e.getCause() instanceof LockBusySkippedException);
                skipped++;
            }
        }
        assertEquals("容量内等待者应全部轮到完成", QUEUE_CAPACITY, completed);
        assertEquals("超容量 1 个立即弃轮", 1, skipped);
        assertEquals("只有真弃轮计数", 1L, port.getLockBusySkipCount());
    }

    /**
     * 契约⑤写路径队满=同步抛出（§3.5 显式背压）：口占用且队列被高优先级填满时，
     * {@code executeWithLambda} 在提交线程同步抛 {@link PortQueueFullException}。
     */
    @Test
    public void executeWithLambdaThrowsSynchronouslyWhenQueueFull() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            SerialTransactionStrategy.executeWithLambda(source,
                    src -> new CompletableFuture<>(), WRITE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        }
        awaitQueued(port, QUEUE_CAPACITY);

        try {
            SerialTransactionStrategy.executeWithLambda(source,
                    src -> CompletableFuture.completedFuture(true),
                    WRITE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("队满时 executeWithLambda 应同步抛 PortQueueFullException");
        } catch (PortQueueFullException expected) {
            assertTrue("PortQueueFullException 应是 LockBusySkippedException 子类（语义声明）",
                    expected instanceof LockBusySkippedException);
        }
        assertTrue(port.release(held));
    }

    /**
     * 契约⑤写路径不回归：口忙时 executeWithLambda 仍按有限等待排队后异常完成——
     * 闸内 IO 体对口的等待语义保留。经等锁可配档重载注入 300ms 短预算缩短验证时长
     * （「等满才异常完成」的语义不变）；生产默认 5s 由下方零耗时常量断言单独锁死。
     */
    @Test
    public void executeWithLambdaKeepsBoundedWait_whenPortBusy() throws Exception {
        SerialSourcePort port = newPort(QUEUE_CAPACITY);
        SerialSource source = newSource(port);
        String held = holdPortOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true),
                WRITE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("口忙时 executeWithLambda 应等待超时后异常完成");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("写路径应保留有限等待（排队至少 " + WRITE_WAIT_MIN_MS + "ms，实际 " + elapsed + "ms）",
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

    // ==================== helpers ====================

    /** 排队数事件等待（deadline 轮询验证事件已发生，超时即失败）。 */
    private static void awaitQueued(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.queuedConversations() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("对话未在 5s 内入队: expected>=" + expected
                        + ", actual=" + port.queuedConversations());
            }
        }
    }

    /** 真弃轮记账事件等待（deadline 轮询验证事件已发生，超时即失败）。 */
    private static void awaitSkipCount(SerialSourcePort port, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.getLockBusySkipCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("弃轮记账未在 5s 内发生: expected>=" + expected
                        + ", actual=" + port.getLockBusySkipCount());
            }
        }
    }
}
