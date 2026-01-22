package com.ecat.integration.SerialIntegration.Listener;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

/**
 * 泛型串口数据监听器对象模版池
 * 使用全局共享池管理，避免 ThreadLocal 在 CompletableFuture 回调中的跨线程问题
 * 统一管理 PooledSerialDataListener 和 BytePooledSerialDataListener
 *
 * @param <T> 监听器类型，必须实现 PoolableListener 接口
 * @author coffee
 */
public class GenericSerialDataListenerPool<T extends PoolableListener> {

    private static final Log log = LogFactory.getLogger(GenericSerialDataListenerPool.class);

    private final ListenerPool pool;
    private final Supplier<T> listenerFactory;
    private final String listenerTypeName;

    /**
     * 内部监听器池实现
     */
    private class ListenerPool {
        private final Queue<T> available;
        private final Set<T> inUse;
        private final int maxSize;
        private int currentSize = 0;
        private final Object lock = new Object();

        ListenerPool(int maxSize) {
            this.maxSize = maxSize;
            this.available = new LinkedList<>();
            this.inUse = new HashSet<>();
        }

        public T acquire() {
            synchronized (lock) {
                T listener = available.poll();

                if (listener == null && currentSize < maxSize) {
                    listener = listenerFactory.get();
                    currentSize++;
                    log.debug("Created new {}, pool size: {}", listenerTypeName, currentSize);
                }

                if (listener != null) {
                    inUse.add(listener);
                }

                return listener;
            }
        }

        public void release(T listener) {
            if (listener != null && currentSize <= maxSize) {
                synchronized (lock) {
                    boolean wasInUse = inUse.remove(listener);

                    if (wasInUse) {
                        listener.cleanup();
                        available.offer(listener);
                    } else {
                        log.warn("Attempting to release {} that is not in use: {}",
                            listenerTypeName, listener);
                    }
                }
            }
        }

        public int getAvailableCount() {
            synchronized (lock) {
                return maxSize - inUse.size();
            }
        }

        public int getInUseCount() {
            synchronized (lock) {
                return inUse.size();
            }
        }

        public int getTotalCount() {
            synchronized (lock) {
                return currentSize;
            }
        }
    }

    /**
     * 构造函数
     *
     * @param listenerFactory 监听器工厂方法
     * @param listenerTypeName 监听器类型名称（用于日志）
     * @param maxSize 池最大大小
     */
    public GenericSerialDataListenerPool(Supplier<T> listenerFactory, String listenerTypeName, int maxSize) {
        this.listenerFactory = listenerFactory;
        this.listenerTypeName = listenerTypeName;
        this.pool = new ListenerPool(maxSize);
    }

    /**
     * 从全局池中获取监听器
     *
     * @return 可用的监听器，如果池已满且没有可用监听器则返回null
     */
    public T acquire() {
        T listener = pool.acquire();

        if (listener == null) {
            log.warn("{} pool exhausted. Available={}, Total={}",
                listenerTypeName, pool.getAvailableCount(), pool.getTotalCount());
        } else {
            log.debug("Acquired {}. Available={}, Total={}",
                listener.toString(), pool.getAvailableCount(), pool.getTotalCount());
        }

        return listener;
    }

    /**
     * 将监听器释放回全局池中
     *
     * @param listener 要释放的监听器
     */
    public void release(T listener) {
        if (listener != null) {
            log.debug("Releasing {}. Available={} before release",
                listener.toString(), pool.getAvailableCount());

            pool.release(listener);
        } else {
            log.warn("Attempted to release null {}", listenerTypeName);
        }
    }

    /**
     * 获取全局池中可用监听器数量
     *
     * @return 可用监听器数量
     */
    public int getAvailableCount() {
        return pool.getAvailableCount();
    }

    /**
     * 获取全局池中使用中的监听器数量
     *
     * @return 使用中监听器数量
     */
    public int getInUseCount() {
        return pool.getInUseCount();
    }

    /**
     * 获取全局池中监听器总数
     *
     * @return 监听器总数
     */
    public int getTotalCount() {
        return pool.getTotalCount();
    }

    /**
     * 清理全局监听器池（保留用于兼容性，实际不执行操作）
     */
    public void cleanup() {
        log.debug("Cleanup called on global {} pool (no-op)", listenerTypeName);
    }
}
