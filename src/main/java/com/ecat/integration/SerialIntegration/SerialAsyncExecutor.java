package com.ecat.integration.SerialIntegration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

/**
 * Serial 集成模块统一的异步执行器
 * 为所有串口相关的异步操作提供统一的线程池
 *
 * @author coffee
 */
public class SerialAsyncExecutor {
    private static final Log log = LogFactory.getLogger(SerialAsyncExecutor.class);

    /**
     * 统一的串口异步操作线程池
     * 配置：核心4线程，最大8线程，队列容量200
     */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        4,                                      // 核心线程数
        8,                                      // 最大线程数
        60L,                                    // 空闲线程存活时间
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(200),         // 队列大小
        r -> {
            Thread t = new Thread(r);
            t.setName("serial-async-" + t.getId());
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时在调用线程执行
    );

    /**
     * 获取统一的异步执行器
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
        ThreadPoolExecutor executor = (ThreadPoolExecutor) EXECUTOR;
        return String.format("SerialAsyncExecutor[queue=%d, active=%d, completed=%d, poolSize=%d]",
                            executor.getQueue().size(),
                            executor.getActiveCount(),
                            executor.getCompletedTaskCount(),
                            executor.getPoolSize());
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
