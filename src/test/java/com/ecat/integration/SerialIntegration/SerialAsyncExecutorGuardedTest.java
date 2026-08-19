package com.ecat.integration.SerialIntegration;

import com.ecat.core.Utils.Mdc.TraceContext;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SerialAsyncExecutor 执行通道换 guarded 视图（arch-review 27 号组件示范接入）契约测试：
 * 任务照常执行、MDC/traceId 照常传播（GuardedExecutor 内建）、状态查询接口不受影响。
 * 硬超时执法本身由 ecat-core GuardedExecutorTest 锁定，此处不重复 60s 级慢路径。
 *
 * @author coffee
 */
public class SerialAsyncExecutorGuardedTest {

    @Test(timeout = 10000)
    public void executorRunsTasksAndPropagatesTraceId() throws Exception {
        ExecutorService executor = SerialAsyncExecutor.getExecutor();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> seenTraceId = new AtomicReference<>();

        TraceContext.setTraceId("trace-serial-guarded");
        try {
            executor.execute(() -> {
                seenTraceId.set(TraceContext.getTraceId());
                done.countDown();
            });
        } finally {
            TraceContext.clearTraceId();
        }

        assertTrue("任务应在 guarded 视图上执行完成", done.await(5, TimeUnit.SECONDS));
        assertEquals("traceId 应传播到 worker 线程", "trace-serial-guarded", seenTraceId.get());
        assertTrue("状态查询接口应保持可用: " + SerialAsyncExecutor.getStatus(),
                SerialAsyncExecutor.getStatus().startsWith("SerialAsyncExecutor["));
        // 锁定状态来自 GuardedExecutor 真实账目（原死池版本指标恒零谎报，前缀断言锁不住）
        assertTrue("状态应透传 GuardedExecutor 账目: " + SerialAsyncExecutor.getStatus(),
                SerialAsyncExecutor.getStatus().contains("GuardedExecutor["));
    }
}
