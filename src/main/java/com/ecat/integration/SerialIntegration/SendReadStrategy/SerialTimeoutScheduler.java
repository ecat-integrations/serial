package com.ecat.integration.SerialIntegration.SendReadStrategy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.Mdc.MdcScheduledExecutorService;

/**
 * SerialTimeoutScheduler 提供端口隔离的线程池管理
 * 每个串口端口拥有独立的线程池，避免多端口并发时的任务混淆
 * 同时通过线程池复用避免频繁创建销毁带来的性能问题
 * 
 * @author coffee
 */
public class SerialTimeoutScheduler {

    private static final Log log = LogFactory.getLogger(SerialTimeoutScheduler.class);

    /**
     * 按端口名称缓存的线程池
     * Key: 端口名称 (如 COM1, /dev/ttyUSB0)
     * Value: 该端口专用的线程池
     */
    private static final ConcurrentMap<String, ScheduledExecutorService> schedulerCache =
        new ConcurrentHashMap<>();

    /**
     * 按端口名称追踪活动任务数量
     * 用于监控和调试
     */
    private static final ConcurrentMap<String, Integer> activeTaskCounts =
        new ConcurrentHashMap<>();

    /**
     * 获取指定端口的线程池
     * 如果不存在，则创建一个新的线程池
     *
     * @param portName 串口端口名称
     * @return 端口专用的线程池
     */
    public static ScheduledExecutorService getScheduler(String portName) {
        return schedulerCache.computeIfAbsent(portName, k -> {
            ScheduledExecutorService rawScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SerialTimeoutScheduler-" + portName);
                t.setDaemon(true); // 设为守护线程，避免阻止JVM退出
                t.setPriority(Thread.NORM_PRIORITY - 1); // 稍微降低优先级
                return t;
            });
            // Wrap with MDC to preserve trace context
            ScheduledExecutorService scheduler = MdcScheduledExecutorService.wrap(rawScheduler);

            // 记录活动任务计数
            activeTaskCounts.put(portName, 0);

            // 记录日志
            log.debug("Created new timeout scheduler for port: {}", portName);

            return scheduler;
        });
    }

    /**
     * 为指定端口调度一个延迟任务
     *
     * @param portName 端口名称
     * @param command 要执行的任务
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return ScheduledFuture 对象，可用于取消任务
     */
    public static ScheduledFuture<?> schedule(String portName, Runnable command,
                                              long delay, TimeUnit unit) {
        ScheduledExecutorService scheduler = getScheduler(portName);
        activeTaskCounts.merge(portName, 1, Integer::sum);

        // 创建包装任务，在任务完成后减少计数
        Runnable wrappedCommand = () -> {
            try {
                command.run();
            } finally {
                // 任务完成后减少计数
                activeTaskCounts.computeIfPresent(portName, (k, count) -> count > 1 ? count - 1 : null);
            }
        };

        return scheduler.schedule(wrappedCommand, delay, unit);
    }

    /**
     * 清理指定端口的线程池
     * 当端口关闭或不再使用时调用
     *
     * @param portName 端口名称
     */
    public static void cleanupScheduler(String portName) {
        ScheduledExecutorService scheduler = schedulerCache.remove(portName);
        if (scheduler != null) {
            log.info("Cleaning up scheduler for port: {}", portName);
            activeTaskCounts.remove(portName);

            try {
                scheduler.shutdown();
                // 等待5秒优雅关闭
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Forcefully shutting down scheduler for port: {}", portName);
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while shutting down scheduler for port: {}, Error: {}",
                        portName, e.getMessage());
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 清理所有线程池
     * 应用关闭时调用
     */
    public static void cleanupAllSchedulers() {
        log.info("Cleaning up all timeout schedulers");
        schedulerCache.keySet().forEach(SerialTimeoutScheduler::cleanupScheduler);
    }

    /**
     * 获取当前活跃的线程池数量
     *
     * @return 活跃线程池数量
     */
    public static int getActiveSchedulerCount() {
        return schedulerCache.size();
    }

    /**
     * 获取指定端口的活动任务数量
     *
     * @param portName 端口名称
     * @return 活动任务数量，如果端口不存在返回0
     */
    public static int getActiveTaskCount(String portName) {
        return activeTaskCounts.getOrDefault(portName, 0);
    }

    /**
     * 获取所有端口的活动任务数量
     *
     * @return 端口名称到活动任务数量的映射
     */
    public static ConcurrentMap<String, Integer> getActiveTaskCounts() {
        return new ConcurrentHashMap<>(activeTaskCounts);
    }

    /**
     * 检查指定端口是否有活动任务
     *
     * @param portName 端口名称
     * @return 如果有活动任务返回true
     */
    public static boolean hasActiveTasks(String portName) {
        return activeTaskCounts.getOrDefault(portName, 0) > 0;
    }

    /**
     * JVM关闭钩子，确保所有线程池被正确关闭
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook: cleaning up SerialTimeoutScheduler");
            cleanupAllSchedulers();
        }));
    }
}
