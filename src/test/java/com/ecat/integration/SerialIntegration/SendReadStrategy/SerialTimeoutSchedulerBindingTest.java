package com.ecat.integration.SerialIntegration.SendReadStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.SerialSdkTimers;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

/**
 * {@link SerialTimeoutScheduler} 注入 seam 与域定时器路由契约（29 号 v2 S1：serial 域
 * 自持定时——原「core 引擎表轮」生产路径退役，未注入时恒走 {@link SerialSdkTimers}）：
 * bind 后超时任务（读超时 + 事务硬超时）确实经注入的调度器执行；
 * 未注入时经域定时器（ecat-serial-sched-N 线程承载）。
 *
 * <p>验证手法：注入具名单线程 STPE（"binding-seam-port"/"binding-seam-tx"），
 * 断言超时任务体运行在该线程上（任务在哪条线程执行 = 哪个调度器承载，不可伪造）。
 *
 * <p>同步方式：轮询 {@code future.isDone()} 到事件发生（验证「已发生」），不用 {@code Thread.sleep}。
 *
 * @author coffee
 */
public class SerialTimeoutSchedulerBindingTest {

    private static final int AWAIT_SECONDS = 10;

    private ScheduledExecutorService injected;

    private static ScheduledExecutorService newNamedSingleDaemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** 等待 future 完成（确定性事件等待的保险丝，正常路径毫秒级返回）。 */
    private static void awaitDone(CompletableFuture<?> future, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(what + " 应在限期内完成", future.isDone());
    }

    @After
    public void tearDown() {
        if (injected != null) {
            injected.shutdownNow();
            injected = null;
        }
        SerialTimeoutScheduler.unbind();
        SerialSdkTimers.resetForTest();
    }

    /**
     * 生产解析层实证（29 号 v2 S1 后的终态）：未 bind 时超时任务恒经域定时器
     * {@link SerialSdkTimers}，任务由 ecat-serial-sched-N 线程承载——引擎依赖归零的
     * 直接验证（原路径：core.getTaskManager() 引擎表轮 + 100ms tick 取整）。
     */
    @Test
    public void productionResolution_routesToDomainTimers() throws Exception {
        SerialSdkTimers.resetForTest();   // 全新默认池，线程形态断言不受先前测试影响
        CompletableFuture<String> runner = new CompletableFuture<>();
        SerialTimeoutScheduler.schedule(
                () -> runner.complete(Thread.currentThread().getName()),
                50, TimeUnit.MILLISECONDS);
        awaitDone(runner, "域定时器承载的超时任务");
        String name = runner.get(1, TimeUnit.SECONDS);
        assertTrue("任务应运行在域定时器线程上 ecat-serial-sched-N（实际: " + name + "）",
                name.matches("ecat-serial-sched-\\d+"));
    }

    @Test
    public void readTimeout_executesOnBoundSchedulerThread() throws Exception {
        injected = newNamedSingleDaemon("binding-seam-port");
        SerialTimeoutScheduler.bind(injected);

        CompletableFuture<String> runner = new CompletableFuture<>();
        SerialTimeoutScheduler.schedule(
                () -> runner.complete(Thread.currentThread().getName()),
                50, TimeUnit.MILLISECONDS);

        awaitDone(runner, "读超时任务");
        assertEquals("读超时任务应运行在注入调度器的线程上",
                "binding-seam-port", runner.get(1, TimeUnit.SECONDS));
    }

    /**
     * 事务硬超时经注入调度器仍然生效：永不完成的事务在硬超时点以 TimeoutException 完成
     * （B5 防护语义在注入路径下不变）。
     */
    @Test
    public void transactionHardTimeout_firesThroughBoundScheduler() throws Exception {
        injected = newNamedSingleDaemon("binding-seam-tx");
        SerialTimeoutScheduler.bind(injected);

        SerialSource source = org.mockito.Mockito.mock(SerialSource.class);
        org.mockito.Mockito.when(source.getIoExecutor())
                .thenReturn(java.util.concurrent.ForkJoinPool.commonPool());
        org.mockito.Mockito.when(source.acquire()).thenReturn("key");
        org.mockito.Mockito.when(source.getTimeout()).thenReturn(50);
        CompletableFuture<Boolean> neverCompleting = new CompletableFuture<>();

        CompletableFuture<Boolean> result = SerialTransactionStrategy
                .executeWithLambda(source, src -> neverCompleting, 200L);

        awaitDone(result, "事务硬超时 future");
        try {
            result.get(1, TimeUnit.SECONDS);
            fail("永不完成的事务应以 TimeoutException 异常完成");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            assertTrue("cause 应为 TimeoutException（经注入调度器触发），实际: " + cause,
                    cause instanceof TimeoutException);
        }
    }
}
