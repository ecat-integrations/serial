package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
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

/**
 * 【E2/R3 终态修复，方案 c】轮询路径 acquire 非阻塞化（调度三原则「过期即弃」）的契约测试。
 *
 * <p>红测形态（修复前）：轮询任务体经 {@code executeWithLambda} 取锁，锁忙时
 * {@code SerialSourcePort.acquire()} 在 waitQueue 上 park 等待（默认 5s）——6 worker × 87
 * 设备秒级阻塞事务互相排队即饱和震荡（Q-F 终验 WEDGE-RECOVERY ~30/min 不收敛）。
 *
 * <p>测试结构：锁状态机用真实 {@code SerialSourcePort}（构造不触碰 jSerialComm，
 * 与 {@code SerialSourcePortGhostLockReapTest} 同边界）；策略层经 mock {@code SerialSource}
 * 把 acquire/tryAcquire/release 桥接到该真实端口——计时断言穿过完整策略链路，
 * 且无需真实串口设备。
 *
 * <p>契约：
 * <ul>
 *   <li>①{@code tryAcquire}/{@code executePolling}：锁忙时本周期立即放弃（毫秒级返回，
 *       调用线程零 park、不进 waitQueue、不消费 signal），future 以
 *       {@link LockBusySkippedException} 异常完成；</li>
 *   <li>②放弃有记账：{@code getLockBusySkipCount()} 递增（禁静默）；</li>
 *   <li>③写命令路径（{@code executeWithLambda}）不回归：锁忙仍按有限等待 park
 *       （MANUAL_COMMAND 经闸的等待语义保留）。</li>
 * </ul>
 *
 * <p>时间上界断言是「无 acquire park」的确定性判据（等价 jstack 式检查：park 必然表现为
 * 秒级耗时）；同步一律用 future.get(timeout)/latch，无 Thread.sleep 猜测。
 */
public class SerialPollingNonBlockingTest {

    /** 轮询入口的非阻塞上界：远小于默认 5s 等锁 park；留足 CI 慢机余量。 */
    private static final long NON_BLOCKING_BOUND_MS = 2_000;
    /** 写路径注入的短等锁预算（经可配档重载显式传入）：主用例不再烧满默认 5s。 */
    private static final long WRITE_LOCK_WAIT_MS = 300;
    /** 写路径有限等待的 park 下界：证明仍在等锁（注入 300ms 的一半以上；
     *  生产默认 5s 的契约面由 {@link #writePathDefaultLockWaitRemainsFiveSeconds()} 常量断言守卫）。 */
    private static final long WRITE_WAIT_MIN_MS = 250;

    /** 真实锁状态机端口（不 openPort，构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("POLL-NB-PORT", 9600, 8, 1, 0), 1, null);
    }

    /** 桥接到真实端口的 SerialSource mock：策略链路的计时/状态断言穿过真实锁状态机。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.tryAcquire()).thenAnswer(inv -> port.tryAcquire());
        when(source.getLockBusySkipCount()).thenAnswer(inv -> port.getLockBusySkipCount());
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        return source;
    }

    /**
     * 异线程持锁构造（36 号守卫后「锁忙」用例的标准前置）：生产中持锁者恒为别的事务线程
     * （写闸线程/轮询 worker），本测试类的锁忙用例原先以测试线程自持再取——那是同线程
     * 嵌套取锁（自死锁形态），守卫上线后立即抛。持锁者改为辅助线程取得后直接退出
     * （不 release：锁状态在端口对象上，与持锁线程存活无关），释放由测试线程按 key 执行。
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

    /**
     * 契约①端口级：锁忙时 tryAcquire 立即返回 null（非阻塞），且不污染等待队列
     * （释放后下一个 acquire 直接可得，无死 key 残留）。
     */
    @Test
    public void tryAcquireReturnsNullImmediately_whenLockHeld_andLeavesWaitQueueClean() throws Exception {
        SerialSourcePort port = newPort();
        String held = holdLockOnHelperThread(port);

        long start = System.currentTimeMillis();
        String busy = port.tryAcquire();
        long elapsed = System.currentTimeMillis() - start;

        assertNull("锁忙时 tryAcquire 应立即放弃返回 null", busy);
        assertTrue("tryAcquire 必须非阻塞（耗时 " + elapsed + "ms 应远小于等待超时）",
                elapsed < NON_BLOCKING_BOUND_MS);

        // 契约②：放弃有记账
        assertEquals("锁忙放弃应计数 1 次", 1L, port.getLockBusySkipCount());

        // 无 waiter 泄漏：释放后快速路径立即可得（若 tryAcquire 曾入队残留死 key，
        // signal 唤醒的等待者会因队头不匹配被拒，此处的 acquire 需等满超时）
        assertTrue(port.release(held));
        String next = port.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("tryAcquire 不得污染 waitQueue（释放后应立即取得锁）", next);
        port.release(next);
    }

    /**
     * 契约①策略级【红→绿主证】：锁忙时 executePolling 的 future 毫秒级以
     * LockBusySkippedException 完成——修复前同一断言对 executeWithLambda 必红
     * （park 满 5s 等待超时后才异常完成）。
     */
    @Test
    public void executePollingSkipsImmediatelyWithLockBusySkipped_whenLockHeld() throws Exception {
        SerialSourcePort port = newPort();
        String held = holdLockOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executePolling(
                bridgedSource(port), src -> CompletableFuture.completedFuture(true));
        try {
            future.get(NON_BLOCKING_BOUND_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("锁忙时 executePolling 应异常完成而非成功");
        } catch (ExecutionException e) {
            assertTrue("异常类型应为 LockBusySkippedException（调用方可识别为本轮跳过），实际: " + e.getCause(),
                    e.getCause() instanceof LockBusySkippedException);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("executePolling 必须毫秒级返回（耗时 " + elapsed + "ms），不得 park 等锁",
                elapsed < NON_BLOCKING_BOUND_MS);
        assertEquals(1L, port.getLockBusySkipCount());

        // 锁空闲时 executePolling 正常执行事务并释放
        assertTrue(port.release(held));
        CompletableFuture<Boolean> ok = SerialTransactionStrategy.executePolling(
                bridgedSource(port), src -> CompletableFuture.completedFuture(true));
        assertTrue(ok.get(5, TimeUnit.SECONDS));
        // 事务结束后锁已释放：可再次取得
        String again = port.acquire(200, TimeUnit.MILLISECONDS);
        assertNotNull("事务完成后锁应已释放", again);
        port.release(again);
    }

    /**
     * 契约③写路径不回归：锁忙时 executeWithLambda 仍按有限等待 park 后异常完成——
     * 闸内 IO 体对锁的等待语义保留，非阻塞化只作用于轮询入口。经等锁可配档重载注入
     * 300ms 短预算缩短验证时长（「等满才异常完成」的语义不变）；生产默认 5s 由下方
     * 零耗时常量断言单独锁死。
     */
    @Test
    public void executeWithLambdaKeepsBoundedWait_whenLockHeld() throws Exception {
        SerialSourcePort port = newPort();
        String held = holdLockOnHelperThread(port);

        long start = System.currentTimeMillis();
        CompletableFuture<Boolean> future = SerialTransactionStrategy.executeWithLambda(
                bridgedSource(port), src -> CompletableFuture.completedFuture(true),
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
     * 契约③常量面（零耗时纯断言）：写路径等锁默认预算 = 5s
     * （{@link SerialSourcePort#DEFAULT_ACQUIRE_WAIT_SECONDS}，{@code acquire()} 无参入口取值）。
     * 主用例注入 300ms 短预算后，默认值漂移（如有人改小）不会再被任何用例察觉——
     * 本断言以纯常量锁死生产默认，零等待。
     */
    @Test
    public void writePathDefaultLockWaitRemainsFiveSeconds() {
        assertEquals(5L, SerialSourcePort.DEFAULT_ACQUIRE_WAIT_SECONDS);
    }

    /** 契约①并发形态：多设备同口轮询互相不 park——持锁期间 N 个 tryAcquire 全部立即放弃。 */
    @Test
    public void concurrentPollersAllSkipImmediately_whenLockHeld() throws Exception {
        SerialSourcePort port = newPort();
        String held = holdLockOnHelperThread(port);

        SerialSource source = bridgedSource(port);
        int pollers = 4;
        CountDownLatch done = new CountDownLatch(pollers);
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] results = new CompletableFuture[pollers];
        for (int i = 0; i < pollers; i++) {
            results[i] = SerialTransactionStrategy.executePolling(
                    source, src -> CompletableFuture.completedFuture(true));
            results[i].whenComplete((r, e) -> done.countDown());
        }
        assertTrue("全部轮询 future 应立即完成（无 park）",
                done.await(NON_BLOCKING_BOUND_MS, TimeUnit.MILLISECONDS));
        for (CompletableFuture<Boolean> f : results) {
            assertTrue(f.isCompletedExceptionally());
        }
        assertEquals(pollers, port.getLockBusySkipCount());
        assertTrue(port.release(held));
    }
}
