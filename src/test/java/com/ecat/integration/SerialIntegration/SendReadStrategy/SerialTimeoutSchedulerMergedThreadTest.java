package com.ecat.integration.SerialIntegration.SendReadStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

/**
 * B3 契约：serial 超时调度合并进共享调度器后，集成不得再自建专用超时调度线程。
 *
 * <p>旧实现（合并前）的线程全景：
 * <ul>
 *   <li>{@code SerialTimeoutScheduler-<port>}：每端口一根 STPE 线程（实测 ~25 根，modbus/serial 组 47 的大头）</li>
 *   <li>{@code serial-tx-hard-timeout}：事务级硬超时计时器（全局 1 根 static final STPE）</li>
 * </ul>
 *
 * <p>本测试类只用既有 public API（不依赖新注入 seam），保证可在旧实现上编译运行并<b>红</b>——
 * 证明「确实走了旧的自建线程路径」；合并后转绿。
 *
 * <p>同步方式：{@link CountDownLatch#await(long, TimeUnit)} 等待事件发生（验证「已发生」），
 * 不用 {@code Thread.sleep} 猜测。
 *
 * @author coffee
 */
public class SerialTimeoutSchedulerMergedThreadTest {

    /** 旧实现的专用线程名前缀（合并后不得存在）。 */
    private static final String LEGACY_PORT_SCHEDULER_PREFIX = "SerialTimeoutScheduler-";
    private static final String LEGACY_HARD_TIMEOUT_THREAD = "serial-tx-hard-timeout";

    /** 等待事件的上限（秒）——确定性等待的保险丝，正常路径毫秒级返回。 */
    private static final int AWAIT_SECONDS = 10;

    private static int countThreadsNamed(String prefix) {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static int countThreadsExactlyNamed(String name) {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().equals(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 读超时任务必须照常执行（超时语义不变），但不得跑在 {@code SerialTimeoutScheduler-<port>}
     * 专用线程上——合并后共享调度器（core 引擎或本地兜底）承载。
     *
     * <p>旧实现红：任务线程名即 {@code SerialTimeoutScheduler-<port>}。
     */
    @Test
    public void readTimeoutTask_firesButNotOnDedicatedPortThread() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<String> runnerThreadName = new AtomicReference<>();

        SerialTimeoutScheduler.schedule(
                () -> {
                    runnerThreadName.set(Thread.currentThread().getName());
                    fired.countDown();
                },
                100, TimeUnit.MILLISECONDS);

        assertTrue("超时任务应在延时后执行（超时语义保持）", fired.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        String name = runnerThreadName.get();
        assertFalse("超时任务不得跑在专用线程上（实际: " + name + "）",
                name.startsWith(LEGACY_PORT_SCHEDULER_PREFIX));
    }

    /**
     * 多端口负载后（旧实现每端口各起一根线程），集成自有的专用超时调度线程数必须为 0。
     *
     * <p>旧实现红：N 端口调度后存在 N 根 {@code SerialTimeoutScheduler-*} 线程。
     */
    @Test
    public void dedicatedPortSchedulerThreads_zeroAfterMultiPortLoad() throws Exception {
        CountDownLatch allFired = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            SerialTimeoutScheduler.schedule(allFired::countDown, 100, TimeUnit.MILLISECONDS);
        }
        assertTrue("全部端口超时任务应执行", allFired.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        assertEquals("多端口加载后 SerialTimeoutScheduler 专用线程数应为 0",
                0, countThreadsNamed(LEGACY_PORT_SCHEDULER_PREFIX));
    }

    /**
     * 事务级硬超时仍必然 complete（B5 防护语义不变），且计时不得由
     * {@code serial-tx-hard-timeout} 专用线程承载。
     *
     * <p>旧实现红：static final STPE 在首次 executeWithLambda 后即存在该线程。
     */
    @Test
    public void transactionHardTimeout_firesWithoutDedicatedThread() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key");
        when(source.getTimeout()).thenReturn(50);
        CompletableFuture<Boolean> neverCompleting = new CompletableFuture<>();

        CompletableFuture<Boolean> result = SerialTransactionStrategy
                .executeWithLambda(source, src -> neverCompleting, 200L);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!result.isDone() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue("硬超时应使事务 future 必然 complete（B5 防护）", result.isDone());
        try {
            result.get(1, TimeUnit.SECONDS);
            fail("永不完成的事务应以 TimeoutException 异常完成");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            assertTrue("cause 应为 TimeoutException，实际: " + cause,
                    cause instanceof TimeoutException);
        }
        assertEquals("serial-tx-hard-timeout 专用线程不得存在",
                0, countThreadsExactlyNamed(LEGACY_HARD_TIMEOUT_THREAD));
    }
}
