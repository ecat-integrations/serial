package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * 方向①契约测试：{@link SerialTransactionStrategy#executeWithLambda} 返回的 future <b>必须异步消费</b>
 * （fire-and-forget + {@code whenComplete}），<b>禁止在周期调度任务内 {@code .get(N, TimeUnit)} 阻塞</b>。
 *
 * <p>根因复现（见 bug-record-20260619221536.md「第四次 LIVE 复现」节）：{@code ecat-scheduled} 调度池
 * 仅 2 线程，被设备 {@code start()} 内 {@code executeWithLambda(...).get(10s)} 阻塞式调用<b>饿死</b>
 * → 事务级硬超时失效 → {@code .get()} 超时不 complete 底层 future → 端口锁永久泄漏（B5 的 LIVE 复现路径）。
 *
 * <p>本测试在<b>单线程调度器</b>上对比两种消费范式（同池提交两个任务，测量第二个「标记任务」被推迟多久）：
 * <ul>
 *   <li>{@link #blockingGetOnNeverCompletingFuture_starvesSharedScheduler()}：阻塞式 {@code .get()}
 *       会独占调度线程至超时 → 标记任务被推迟（复现饥饿机制，即被禁用的反模式）。</li>
 *   <li>{@link #asyncConsumption_doesNotStarveSharedScheduler()}：异步 {@code whenComplete} 不占用调度线程
 *       → 标记任务即时执行（即各设备 {@code start()} 应遵循的正确范式，对齐 modbus / sailhero XH*、PM3000E）。</li>
 * </ul>
 *
 * <p>注：方向①的生产行为变更在<b>设备层</b>（9 个设备 {@code start()} 去 {@code .get()} 改 {@code whenComplete}），
 * 其回归由各集成模块既有单测 + LIVE 自恢复实证覆盖；本类在事务策略层固化「禁止阻塞消费」的契约。
 *
 * @author coffee
 */
public class SerialTransactionStrategyAsyncContractTest {

    /** 测试用事务硬超时（生产默认见 resolveDefaultTransactionTimeoutMs / Const.READ_TIMEOUT_MS） */
    private static final long TX_TIMEOUT_MS = 300L;
    /** 阻塞反模式演示用的短 .get() 超时（取小值让用例快速，仅演示机制） */
    private static final long BLOCKING_GET_MS = 250L;

    private static SerialSource newMockSource() {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key");
        when(source.getTimeout()).thenReturn(50);
        return source;
    }

    /** 单线程调度器工厂 —— 等价于被共享的 ecat-scheduled 池里的一条线程。 */
    private static ScheduledExecutorService newSingleThreadScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** 等待标记任务执行，返回其相对起始时刻的延迟（毫秒）；超时未跑返回 -1。 */
    private static long awaitMarkerDelayMs(AtomicLong markerRunAt, long t0Nano, long waitSec) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSec);
        while (markerRunAt.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (markerRunAt.get() == 0) {
            return -1;
        }
        return TimeUnit.NANOSECONDS.toMillis(markerRunAt.get() - t0Nano);
    }

    /**
     * 【反模式 characterization】阻塞式 {@code .get(N)} 消费一个永不 complete 的 future 时，
     * 会独占调度线程至 {@code .get()} 超时 → 同池的标记任务被推迟约 {@link #BLOCKING_GET_MS}。
     *
     * <p>此用例刻画「为何禁止 {@code .get()}」：在 LIVE 多设备工况下，多个这样的阻塞调用
     * 同时占满仅 2 线程的 ecat-scheduled 池 → 全平台周期任务停摆（实证见 bug-record「第四次 LIVE 复现」）。
     */
    @Test
    public void blockingGetOnNeverCompletingFuture_starvesSharedScheduler() throws Exception {
        SerialSource source = newMockSource();
        CompletableFuture<Boolean> neverCompleting = new CompletableFuture<>();
        ScheduledExecutorService pool = newSingleThreadScheduler();
        try {
            long t0 = System.nanoTime();
            // 任务1：阻塞反模式 —— .get() 独占线程至超时
            pool.submit(() -> {
                try {
                    SerialTransactionStrategy
                            .executeWithLambda(source, src -> neverCompleting, TX_TIMEOUT_MS)
                            .get(BLOCKING_GET_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // .get() 超时（future 未 complete）抛 TimeoutException，预期
                }
            });
            AtomicLong markerRunAt = new AtomicLong(0);
            pool.submit(() -> markerRunAt.set(System.nanoTime()));

            long markerDelayMs = awaitMarkerDelayMs(markerRunAt, t0, 3);
            assertTrue("标记任务应被执行", markerDelayMs >= 0);
            // 阻塞 .get() 独占线程：标记任务被推迟至接近 .get() 超时（证明饥饿）
            assertTrue("阻塞式 .get() 应饿死调度池：标记任务应被推迟 ≥ " + (BLOCKING_GET_MS - 50)
                    + "ms，实际 " + markerDelayMs + "ms", markerDelayMs >= BLOCKING_GET_MS - 50);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 【正确范式 GREEN】异步消费（{@code whenComplete}，不 {@code .get()}）一个永不 complete 的 future 时，
     * 调度线程立即返回（硬超时计时在独立的 HARD_TIMEOUT_SCHEDULER 上）→ 同池标记任务<b>即时执行，不饥饿</b>。
     *
     * <p>这是各设备 {@code start()} 必须遵循的契约（对齐 modbus 与 sailhero XH*、PM3000E 等既有设备）。
     */
    @Test
    public void asyncConsumption_doesNotStarveSharedScheduler() throws Exception {
        SerialSource source = newMockSource();
        CompletableFuture<Boolean> neverCompleting = new CompletableFuture<>();
        ScheduledExecutorService pool = newSingleThreadScheduler();
        try {
            long t0 = System.nanoTime();
            // 任务1：正确范式 —— 异步消费，调度线程不在此阻塞
            pool.submit(() ->
                SerialTransactionStrategy
                        .executeWithLambda(source, src -> neverCompleting, TX_TIMEOUT_MS)
                        .whenComplete((r, e) -> { /* 异步处理结果/异常；release 由策略内部 whenCompleteAsync 负责 */ }));
            AtomicLong markerRunAt = new AtomicLong(0);
            pool.submit(() -> markerRunAt.set(System.nanoTime()));

            long markerDelayMs = awaitMarkerDelayMs(markerRunAt, t0, 3);
            assertTrue("标记任务应被执行", markerDelayMs >= 0);
            // 异步消费不占用调度线程：标记任务应在 200ms 内执行（与阻塞用例的 ~250ms 形成鲜明对比）
            assertTrue("异步消费不应饿死调度池：标记任务应在 200ms 内执行，实际 " + markerDelayMs + "ms",
                    markerDelayMs < 200);
        } finally {
            pool.shutdownNow();
        }
    }
}
