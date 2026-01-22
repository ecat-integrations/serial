package com.ecat.integration.SerialIntegration.Listener;

/**
 * 串口数据监听器对象模版池
 * 委托给泛型池 {@link SerialListenerPools#STRING_POOL} 实现
 * 使用线程本地存储避免并发竞争，降低内存占用，提高性能
 *
 * @author coffee
 */
public class SerialDataListenerPool {

    /**
     * 从当前线程的池中获取监听器
     *
     * @return 可用的监听器
     */
    public static PooledSerialDataListener acquire() {
        return SerialListenerPools.STRING_POOL.acquire();
    }

    /**
     * 将监听器释放回当前线程的池中
     *
     * @param listener 要释放的监听器
     */
    public static void release(PooledSerialDataListener listener) {
        SerialListenerPools.STRING_POOL.release(listener);
    }

    /**
     * 获取当前线程池中可用监听器数量
     * 主要用于监控和调试
     *
     * @return 可用监听器数量
     */
    public static int getAvailableCount() {
        return SerialListenerPools.STRING_POOL.getAvailableCount();
    }

    /**
     * 获取当前线程池中使用中的监听器数量
     * 主要用于监控和调试
     *
     * @return 使用中监听器数量
     */
    public static int getInUseCount() {
        return SerialListenerPools.STRING_POOL.getInUseCount();
    }

    /**
     * 获取当前线程池中监听器总数
     * 主要用于监控和调试
     *
     * @return 监听器总数
     */
    public static int getTotalCount() {
        return SerialListenerPools.STRING_POOL.getTotalCount();
    }

    /**
     * 清理当前线程的监听器池
     * 通常在线程结束时调用
     */
    public static void cleanup() {
        SerialListenerPools.STRING_POOL.cleanup();
    }
}
