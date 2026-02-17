package com.ecat.integration.SerialIntegration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.Mdc.MdcExecutorService;

/**
 * Serial 集成模块统一的异步执行器
 * 为所有串口相关的异步操作提供统一的线程池
 *
 * <p>已包装 MDC 上下文传播，确保异步线程中的日志正确路由到对应集成。
 *
 * @author coffee
 */
public class SerialAsyncExecutor {
    private static final Log log = LogFactory.getLogger(SerialAsyncExecutor.class);

    /**
     * 原始线程池（用于状态查询和关闭）
     * 使用 NamedThreadFactory 命名，线程名格式: integration-serial-0, integration-serial-1
     */
    private static final ThreadPoolExecutor RAW_EXECUTOR = new ThreadPoolExecutor(
        4,                                      // 核心线程数
        8,                                      // 最大线程数
        60L,                                    // 空闲线程存活时间
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(200),         // 队列大小
        new NamedThreadFactory("integration-serial"),  // 线程名前缀
        new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时在调用线程执行
    );

    /**
     * MDC 包装的统一串口异步操作线程池
     * 自动传播 MDC 上下文到异步线程
     */
    private static final ExecutorService EXECUTOR = MdcExecutorService.wrap(RAW_EXECUTOR);

    /**
     * 获取统一的异步执行器（已包装 MDC）
     * @return ExecutorService 线程池
     */
    public static ExecutorService getExecutor() {
        return EXECUTOR;
    }

    /**
     * 获取线程池状态信息
     * @return 状态字符串
     */
    public static String getStatus() {
        return String.format("SerialAsyncExecutor[queue=%d, active=%d, completed=%d, poolSize=%d]",
                            RAW_EXECUTOR.getQueue().size(),
                            RAW_EXECUTOR.getActiveCount(),
                            RAW_EXECUTOR.getCompletedTaskCount(),
                            RAW_EXECUTOR.getPoolSize());
    }

    /**
     * 关闭线程池（应用关闭时调用）
     */
    public static void shutdown() {
        log.info("Shutting down SerialAsyncExecutor");
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Forcefully shutting down SerialAsyncExecutor");
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // JVM关闭钩子
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdown();
        }));
    }
}
