package com.ecat.integration.SerialIntegration.Listener;

/**
 * 串口监听器池单例管理类
 * 统一管理字节监听器池
 *
 * @author coffee
 */
public class SerialListenerPools {

    /**
     * 字节监听器池
     * 管理 BytePooledSerialDataListener
     */
    public static final GenericSerialDataListenerPool<BytePooledSerialDataListener> BYTE_POOL =
        new GenericSerialDataListenerPool<>(
            BytePooledSerialDataListener::new,
            "ByteListener",
            20
        );

    private SerialListenerPools() {
        // 私有构造函数，防止实例化
    }
}
