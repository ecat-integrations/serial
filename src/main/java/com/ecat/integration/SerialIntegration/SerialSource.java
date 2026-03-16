package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

/**
 * SerialSource is a class that manages serial port communication
 * and provides a locking mechanism to ensure
 * exclusive access to the serial port.
 *
 * Each SerialSource holds its own identity and listener list,
 * and delegates I/O and lock operations to a shared SerialSourcePort.
 *
 * @author coffee
 */
public class SerialSource {
    private static final Log log = LogFactory.getLogger(SerialSource.class);

    private final String identity;

    /**
     * 获取此 SerialSource 的标识符
     */
    String getIdentity() {
        return identity;
    }
    private final SerialSourcePort sourcePort;
    private final CopyOnWriteArrayList<SerialDataListener> dataListeners = new CopyOnWriteArrayList<>();

    /**
     * Primary constructor used by SerialIntegration.
     * Each call to register() creates a new SerialSource with its own identity.
     */
    SerialSource(SerialSourcePort sourcePort, String identity) {
        this.sourcePort = sourcePort;
        this.identity = identity;
        sourcePort.registerSource(this);
        log.info("SerialSource created for port: " + getPortName() + ", identity: " + identity);
    }

    /**
     * Backward-compatible constructor — creates an internal SerialSourcePort.
     */
    public SerialSource(SerialInfo serialInfo) {
        this(serialInfo, 1);
    }

    /**
     * Backward-compatible constructor — creates an internal SerialSourcePort.
     */
    public SerialSource(SerialInfo serialInfo, int maxWaiters) {
        this.sourcePort = new SerialSourcePort(serialInfo, maxWaiters, null);
        this.identity = "standalone";
        sourcePort.registerSource(this);
        log.info("SerialSource created (standalone) for port: " + getPortName());
    }

    // ========== Delegating methods (→ sourcePort) ==========

    /**
     * @deprecated 推荐使用 {@link #asyncSendData(byte[])} 方法
     */
    @Deprecated
    public CompletableFuture<Boolean> asyncSendData(String data) {
        byte[] bytes = data != null ? data.getBytes() : new byte[0];
        return asyncSendData(bytes);
    }

    /**
     * 新的主力发送数据方法 - 发送字节数组
     * 使用独立的串口通信线程池，避免被其他任务阻塞
     * @param bytes 要发送的字节数组
     * @return CompletableFuture<Boolean> 发送结果
     */
    public CompletableFuture<Boolean> asyncSendData(byte[] bytes) {
        return sourcePort.asyncSendData(bytes);
    }

    /**
     * @deprecated 只为兼容其他集成旧测试用例，新的要使用 {@link #asyncReadDataBytes()} 方法
     */
    @Deprecated
    public CompletableFuture<String> asyncReadData() {
        return asyncReadDataBytes().thenApply(bytes -> {
            if (bytes != null && bytes.length > 0) {
                return new String(bytes);
            }
            return "";
        });
    }

    /**
     * 新的主力读取数据方法 - 读取字节数组
     * @return CompletableFuture<byte[]> 读取的字节数组
     */
    public CompletableFuture<byte[]> asyncReadDataBytes() {
        return sourcePort.asyncReadDataBytes();
    }

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return sourcePort.acquire();
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时或超出等待队列容量
     */
    public String acquire(long timeout, TimeUnit unit) {
        return sourcePort.acquire(timeout, unit);
    }

    /**
     * 释放锁
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功
     */
    public boolean release(String releaseKey) {
        return sourcePort.release(releaseKey);
    }

    public boolean isPortOpen() {
        return sourcePort.isPortOpen();
    }

    public String getSystemPortName() {
        return sourcePort.getSystemPortName();
    }
    
    /**
     * 获取端口名称
     * @return 端口名称
     * @throws IllegalStateException 如果端口名称无法获取
     */
    public String getPortName() {
        return sourcePort.getPortName();
    }
    /**
     * 获取底层 SerialPort 对象
     * @return SerialPort 对象
     */
    public SerialPort getSerialPort() {
        return sourcePort.getSerialPort();
    }

    public boolean isTestMode() {
        return sourcePort.isTestMode();
    }

    public boolean isClosed() {
        return sourcePort.isClosed();
    }

    /**
     * 获取当前串口超时设置（毫秒）
     */
    public int getTimeout() {
        return sourcePort.getTimeout();
    }

    // ========== Self-managed listener methods ==========

    /**
     * 添加数据监听器
     */
    public void addDataListener(SerialDataListener listener) {
        if (listener != null && !dataListeners.contains(listener)) {
            dataListeners.add(listener);
            log.debug(getPortName() + " [" + identity + "] added data listener, total listeners: " + dataListeners.size());
            // 如果发送命令后已经收到了消息，则认为是本次命令的应答，直接将数据发布给监听器
            sourcePort.deliverBufferedData(listener);
        }
    }

    /**
     * 移除数据监听器
     */
    public void removeDataListener(SerialDataListener listener) {
        if (listener != null && dataListeners.remove(listener)) {
            log.debug(getPortName() + " [" + identity + "] removed data listener, total listeners: " + dataListeners.size());
        }
    }

    /**
     * 移除所有数据监听器
     */
    public void removeAllDataListeners() {
        int count = dataListeners.size();
        dataListeners.clear();
        log.info(getPortName() + " [" + identity + "] removed all data listeners, count: " + count);
    }

    /**
     * 获取当前数据监听器数量
     */
    public int getDataListenerCount() {
        return dataListeners.size();
    }

    /**
     * Notify all listeners on this SerialSource. Called by SerialSourcePort.
     */
    void notifyListeners(byte[] data, int length) {
        for (SerialDataListener listener : dataListeners) {
            try {
                listener.onDataReceived(data, length);
            } catch (Exception e) {
                log.warn(getPortName() + " [" + identity + "] error notifying listener: " + e.getMessage());
                listener.onError(e);
            }
        }
    }

    // ========== Close methods ==========

    /**
     * Close this SerialSource's connection.
     * Removes all listeners and unregisters from the shared port.
     * If this is the last source, the underlying port will be closed.
     */
    public void closePort() {
        removeAllDataListeners();
        sourcePort.unregisterSource(this);
        log.info("SerialSource closed for port: " + getPortName() + ", identity: " + identity);
    }
}
