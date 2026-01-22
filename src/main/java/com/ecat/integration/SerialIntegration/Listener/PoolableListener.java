package com.ecat.integration.SerialIntegration.Listener;

/**
 * 可池化的监听器接口
 * 定义了对象池所需的通用方法
 *
 * @author coffee
 */
public interface PoolableListener {
    /**
     * 清理监听器状态，准备回收到对象池
     */
    void cleanup();

    /**
     * 检查监听器是否正在使用
     *
     * @return 如果正在使用返回true
     */
    boolean isInUse();
}
