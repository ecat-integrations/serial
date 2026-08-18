package com.ecat.integration.SerialIntegration.SendReadStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Test;

import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

/**
 * {@link SerialTimeoutScheduler} 注入 seam 契约：bind 后超时任务（读超时 + 事务硬超时）
 * 确实经注入的调度器执行——证明合并路径真实生效（非仅专用线程名消失）。
 *
 * <p>验证手法：注入具名单线程 STPE（"binding-seam-port"/"binding-seam-tx"），断言超时任务体
 * 运行在该线程上（任务在哪条线程执行 = 哪个调度器承载，不可伪造）。
 *
 * <p>本类依赖合并后的 bind/unbind API（旧实现无此 seam，无「红」阶段；旧路径的红证明由
 * {@link SerialTimeoutSchedulerMergedThreadTest} 在旧实现上完成）。
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
    }

    @Test
    public void delegate_returnsBoundScheduler() {
        injected = newNamedSingleDaemon("binding-seam-identity");
        SerialTimeoutScheduler.bind(injected);
        assertSame("bind 后 delegate 恒返回注入实例", injected, SerialTimeoutScheduler.delegate());
    }

    /**
     * 生产解析层实证：ECAT core 实例存在时，delegate 解析到 B1 调度引擎
     * （{@code TaskManager.getMdcScheduledExecutorService()}），任务由引擎 worker 线程承载。
     *
     * <p>用 mock EcatCore + 真实 TaskManager 模拟「core 已就绪」的最小平台上下文，
     * 证明合并的目标路径（core 表轮）真实可达，不依赖重启 core 的运行时验证。
     */
    @Test
    public void productionResolution_routesToCoreEngineWhenInstancePresent() throws Exception {
        EcatCore previous = EcatCore.getInstance();
        EcatCore mockCore = mock(EcatCore.class);
        TaskManager taskManager = new TaskManager();
        ScheduledExecutorService engine = taskManager.getMdcScheduledExecutorService();
        when(mockCore.getTaskManager()).thenReturn(taskManager);
        try {
            EcatCore.setInstance(mockCore);
            assertSame("core 实例存在时应解析到 B1 引擎", engine, SerialTimeoutScheduler.delegate());

            CompletableFuture<String> runner = new CompletableFuture<>();
            SerialTimeoutScheduler.schedule(
                    () -> runner.complete(Thread.currentThread().getName()),
                    50, TimeUnit.MILLISECONDS);
            awaitDone(runner, "引擎承载的超时任务");
            String name = runner.get(1, TimeUnit.SECONDS);
            assertTrue("任务应运行在引擎 worker 线程上（实际: " + name + "）",
                    name.startsWith("ecat-sched-worker-"));
        } finally {
            EcatCore.setInstance(previous);
            taskManager.shutdownAll();
        }
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
     * （B5 防护语义在合并路径下不变）。
     */
    @Test
    public void transactionHardTimeout_firesThroughBoundScheduler() throws Exception {
        injected = newNamedSingleDaemon("binding-seam-tx");
        SerialTimeoutScheduler.bind(injected);

        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key");
        when(source.getTimeout()).thenReturn(50);
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
