package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

/**
 * 线程模型契约测试（serial IO 最小模型 §3.6）：口执行线程只做排序、IO 发起与事件处理，
 * 永不执行设备代码——设备 lambda（executeWithLambda/executePolling 的事务体）轮到时在
 * IO 工作线程（SerialIoPool 域池）启动，调用线程入队即返回 future 不阻塞；设备可见
 * future 的正常完成点在 IO 工作线程（thenAccept/whenComplete 回调因此永在 worker）；
 * MDC 随任务传播（提交点捕获、worker 上恢复）。acquirePollingBounded 对外行为保持
 * （CF&lt;String&gt;，null 完成=本周期弃轮，授予后可 release 收尾）。
 *
 * <p>线程名判据：IO 池 worker = {@code ecat-serial-io-N}；口执行线程 =
 * {@code ecat-serial-port-*}。断言在事务体内采样（确定性，事件已发生才断言）。
 *
 * @author coffee
 */
public class SerialExecutorThreadingContractTest {

    private SerialSourcePort port;
    private SerialSource session;

    @Before
    public void setUp() {
        port = new SerialSourcePort(new SerialInfo("THREAD-CONTRACT-PORT", 9600, 8, 1, 0), 3, null);
        session = new SerialSource(port, "thread-contract");
    }

    @After
    public void tearDown() {
        MDC.clear();
    }

    /** 契约①：设备 lambda 在 IO 工作线程执行，绝不在口执行线程（口执行线程零设备代码）。 */
    @Test
    public void deviceLambdaRunsOnIoWorkerThread_neverOnPortThread() throws Exception {
        AtomicReference<String> bodyThread = new AtomicReference<>();
        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(session, src -> {
            bodyThread.set(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(true);
        });
        assertTrue(result.get(5, TimeUnit.SECONDS));
        String name = bodyThread.get();
        assertNotNull("事务体必须已执行", name);
        assertTrue("事务体必须在 IO 池工作线程（ecat-serial-io-N）执行，实际: " + name,
                name.matches("ecat-serial-io-\\d+"));
    }

    /**
     * 契约②：完成链在 IO 工作线程——事务体内挂观察者的完成 future 在 worker 上完成，
     * 观察者（thenAccept/whenComplete 回调的第一棒）因此在 IO 工作线程执行。外层 future
     * 的迟到观察者落在已完成 CF 上时按 CF 语义在调用线程立即回调，不属完成点契约面。
     */
    @Test
    public void deviceVisibleFutureCompletesOnIoWorkerThread() throws Exception {
        AtomicReference<String> completionThread = new AtomicReference<>();
        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(session, src -> {
            CompletableFuture<Boolean> inner = new CompletableFuture<>();
            inner.whenComplete((res, ex) -> {
                assertTrue("正常完成不得带异常", ex == null);
                completionThread.set(Thread.currentThread().getName());
            });
            inner.complete(Boolean.TRUE);   // 事务体在 worker 上执行：完成点=worker
            return inner;
        });
        assertEquals(Boolean.TRUE, result.get(5, TimeUnit.SECONDS));
        String name = completionThread.get();
        assertNotNull("完成观察者必须已执行", name);
        assertTrue("完成点必须在 IO 池工作线程，实际: " + name, name.matches("ecat-serial-io-\\d+"));
    }

    /** 契约③：入队即返回（调用线程零 park）——提交耗时毫秒级，远小于事务体自身耗时。 */
    @Test
    public void submissionIsNonBlocking_enqueueReturnsImmediately() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(session, src -> {
            CompletableFuture<Boolean> slow = new CompletableFuture<>();
            SerialSdkTimers.fireAfter(() -> slow.complete(true), 300L);
            return slow;
        });
        long submitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("入队必须毫秒级返回（实际 " + submitMs + "ms，事务体在飞 300ms）", submitMs < 200);
        assertTrue("事务最终完成（300ms 域定时器到点）", result.get(5, TimeUnit.SECONDS));
    }

    /** 契约④：MDC 随任务传播——提交点捕获、worker 上恢复（事务体内读到的键值=提交时快照）。 */
    @Test
    public void mdcCapturedAtSubmitAndRestoredAtWorker() throws Exception {
        MDC.put("coordinate", "com.ecat:thread-contract-test");
        MDC.put("deviceId", "dev-mdc-1");
        AtomicReference<String> coordInBody = new AtomicReference<>();
        AtomicReference<String> deviceInBody = new AtomicReference<>();
        try {
            CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(session, src -> {
                coordInBody.set(MDC.get("coordinate"));
                deviceInBody.set(MDC.get("deviceId"));
                return CompletableFuture.completedFuture(true);
            });
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally {
            MDC.remove("coordinate");
            MDC.remove("deviceId");
        }
        assertEquals("MDC coordinate 必须传播到事务体", "com.ecat:thread-contract-test", coordInBody.get());
        assertEquals("MDC deviceId 必须传播到事务体", "dev-mdc-1", deviceInBody.get());
    }

    /**
     * 契约⑤：acquirePollingBounded 对外行为保持——口闲时授予可 release 收尾；
     * 口忙时排队预算耗尽完成 null（弃轮），不抛不给锁。
     */
    @Test
    public void acquirePollingBounded_externalBehaviorPreserved() throws Exception {
        // 口闲：授予 → release 收尾
        CompletableFuture<String> granted = port.acquirePollingBounded(null, 500L);
        String key = granted.get(2, TimeUnit.SECONDS);
        assertNotNull("口闲时必须授予", key);
        assertTrue("授予的 key 必须 release 成功", port.release(key));

        // 口忙：预算耗尽 → null 完成（弃轮语义），计数记账
        final String[] held = new String[1];
        Thread holder = new Thread(() -> held[0] = port.acquire(10, TimeUnit.SECONDS), "thread-contract-holder");
        holder.setDaemon(true);
        holder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (held[0] == null && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertNotNull("测试前置：占用者必须取得口", held[0]);

        CompletableFuture<String> busy = port.acquirePollingBounded(null, 150L);
        assertNull("预算耗尽必须 null 完成（弃轮）", busy.get(5, TimeUnit.SECONDS));
        assertTrue("真弃轮必须记账", port.getLockBusySkipCount() >= 1);
        assertTrue(port.release(held[0]));
    }
}
