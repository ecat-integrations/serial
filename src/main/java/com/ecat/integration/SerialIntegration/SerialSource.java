package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialSourceEventAdapter;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

/**
 * SerialSource is a class that manages serial port communication
 * and provides a locking mechanism to ensure
 * exclusive access to the serial port.
 * 
 * @author coffee
 */
public class SerialSource {
    private static final int TIMEOUT = 500;
    private static final Log log = LogFactory.getLogger(SerialSource.class);
    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();
    private final int maxWaiters; // 最大等待请求数
    private String currentKey;    // 当前持有锁的key
    private volatile long lockAcquireTime;    // 锁获取时间
    private volatile String lockAcquireThread; // 获取锁的线程名
    private final Queue<String> waitQueue = new LinkedList<>(); // 等待队列（保存请求标识）

    SerialPort serialPort;
    SerialInfo serialInfo;
    List<String> registeredIntegrations;

    // 中断驱动相关字段
    private final DynamicByteArrayBuffer continuousReceiveBuffer = new DynamicByteArrayBuffer(1024, 2.0f);
    private final List<SerialDataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final Lock bufferLock = new ReentrantLock();
    private SerialSourceEventAdapter eventAdapter;
    private boolean isTestMode = false; // 测试环境使用 polling， 生产环境使用 interrupting


    public SerialSource(SerialInfo serialInfo) {
        this(serialInfo, 1); // 默认最大等待请求数为1
    }

    public SerialSource(SerialInfo serialInfo, int maxWaiters) {
        this.maxWaiters = maxWaiters; // 设置资源最大等待请求数
        this.serialInfo = serialInfo;
        this.registeredIntegrations = new ArrayList<>();

        // 检测是否在测试环境
        this.isTestMode = detectTestEnvironment();

        openPort();
    }

    /**
     * @deprecated 推荐使用 {@link #asyncSendData(byte[])} 方法
     * 发送字符串数据
     * @param data 要发送的字符串数据
     * @return CompletableFuture<Boolean> 发送结果
     */
    @Deprecated
    public CompletableFuture<Boolean> asyncSendData(String data) {
        byte[] bytes = data != null ? data.getBytes() : new byte[0];
        return asyncSendData(bytes);
    }
    
    /**
     * @deprecated 只为兼容其他集成旧测试用例，新的要使用 {@link #asyncReadDataBytes()} 方法
     * 读取字符串数据
     * @return CompletableFuture<String> 读取的字符串数据
     */
    @Deprecated
    public CompletableFuture<String> asyncReadData() {
        // asyncReadDataBytes() 已经在 SerialAsyncExecutor 中执行
        // thenApply 会在同一个线程中执行，不会切换到 ForkJoinPool.commonPool
        return asyncReadDataBytes().thenApply(bytes -> {
            if (bytes != null && bytes.length > 0) {
                return new String(bytes);
            }
            return "";
        });
    }

    /**
     * 新的主力发送数据方法 - 发送字节数组
     * 使用独立的串口通信线程池，避免被其他任务阻塞
     * @param bytes 要发送的字节数组
     * @return CompletableFuture<Boolean> 发送结果
     */
    public CompletableFuture<Boolean> asyncSendData(byte[] bytes) {
        return CompletableFuture.runAsync(() -> {
            if(isTestMode){
                // align the buffer - 清空串口缓冲区中的待读数据
                while (serialPort.bytesAvailable() > 0) {
                    serialPort.readBytes(new byte[serialPort.bytesAvailable()], serialPort.bytesAvailable());
                }
            }
            else{
                // 清空连续接收缓冲区
                clearReceiveBuffer();
            }
            // 发送数据
            serialPort.writeBytes(bytes, bytes.length);
        }, SerialAsyncExecutor.getExecutor()).thenApplyAsync(v -> {
            return true;
        }, SerialAsyncExecutor.getExecutor()).exceptionally(ex -> {
            throw new RuntimeException("Failed to send data: " + ex.getMessage());
        });
    }

    /**
     * 新的主力读取数据方法 - 读取字节数组
     * @return CompletableFuture<byte[]> 读取的字节数组
     */
    public CompletableFuture<byte[]> asyncReadDataBytes() {
        if (isTestMode) {
            // 测试模式：保持原有行为，使用轮询方式
            return CompletableFuture.supplyAsync(() -> {
                if (serialPort.isOpen()) {
                    int numRead;
                    byte[] readBuffer = new byte[serialPort.bytesAvailable()];
                    numRead = serialPort.readBytes(readBuffer, readBuffer.length);
                    if (numRead > 0 && numRead < readBuffer.length) {
                        byte[] result = new byte[numRead];
                        System.arraycopy(readBuffer, 0, result, 0, numRead);
                        return result;
                    }
                    return readBuffer;
                }
                return new byte[0];
            }, SerialAsyncExecutor.getExecutor());
        } else {
            // 生产模式：从中断缓冲区读取
            return readFromBufferBytes();
        }
    }

    public void registerIntegration(String identity) {
        registeredIntegrations.add(identity);
    }

    @Deprecated
    protected void removeIntegration(String identity) {
        registeredIntegrations.remove(identity);
    }

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return acquire(5, TimeUnit.SECONDS);
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时或超出等待队列容量
     */
    public String acquire(long timeout, TimeUnit unit) {
        String requestKey = generateRequestKey(); // 生成唯一请求标识
        lock.lock();
        try {
            if (currentKey == null) {
                // 直接获取锁
                currentKey = requestKey;
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                log.info("Lock acquired: {} by thread {} at {}", requestKey, lockAcquireThread, lockAcquireTime);
                return requestKey;
            } else {
                // 检查等待队列是否未满
                if (waitQueue.size() < maxWaiters) {
                    waitQueue.add(requestKey);
                    log.info("Enter wait queue: " + requestKey + ", queue size: " + waitQueue.size());
                    boolean isAwoken = false;
                    try {
                        // 等待唤醒或超时
                        isAwoken = condition.await(timeout, unit);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        log.warn("Wait interrupted: " + requestKey);
                        waitQueue.remove(requestKey); // 从队列移除
                        return null;
                    }
                    
                    if (isAwoken) {
                        // 被唤醒后检查是否轮到自己
                        if (waitQueue.peek() != null && waitQueue.peek().equals(requestKey)) {
                            currentKey = requestKey;
                            waitQueue.poll();
                            lockAcquireTime = System.currentTimeMillis();
                            lockAcquireThread = Thread.currentThread().getName();
                            log.info("Lock acquired after waiting: {} by thread {} at {}", requestKey, lockAcquireThread, lockAcquireTime);
                            return requestKey;
                        } else {
                            log.info("Wait queue changed, skip acquisition: " + requestKey);
                            return null;
                        }
                    } else {
                        // 超时处理
                        waitQueue.remove(requestKey); // 从队列移除超时请求
                        // if (waitQueue.isEmpty()) {
                        //     currentKey = null;
                        // }
                        log.warn("Acquire timeout: {}, lock currently held by: {} (acquired at {} by thread {}), waitQueue size: {}",
                                requestKey, currentKey, lockAcquireTime, lockAcquireThread, waitQueue.size());
                        return null;
                    }
                } else {
                    log.warn("Max waiters exceeded, request rejected: " + requestKey);
                    return null; // 超出最大等待数，直接返回不可用
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放锁
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功
     */
    public boolean release(String releaseKey) {
        lock.lock();
        try {
            if (currentKey != null && currentKey.equals(releaseKey)) {
                long heldDuration = System.currentTimeMillis() - lockAcquireTime;
                String releasingThread = Thread.currentThread().getName();
                log.info("Lock released: {} by thread {} (held by {} for {} ms)",
                        releaseKey, releasingThread, lockAcquireThread, heldDuration);
                currentKey = null;
                lockAcquireTime = 0;
                lockAcquireThread = null;
                // 唤醒下一个等待的请求
                if (!waitQueue.isEmpty()) {
                    // String nextKey = waitQueue.poll();
                    // currentKey = nextKey;
                    // log.log(Level.INFO, "Waking up next waiter: " + nextKey);
                    condition.signal(); // 唤醒等待队列中的第一个线程
                }
                return true;
            }
            log.warn("Invalid release key: " + releaseKey);
            return false;
        } finally {
            lock.unlock();
        }
    }

    private String generateRequestKey() {
        // 生成唯一请求标识（示例：时间戳+线程ID）
        return System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }

    private void openPort() {
        serialPort = SerialPort.getCommPort(serialInfo.portName);
        serialPort.setBaudRate(serialInfo.baudrate);
        serialPort.setNumDataBits(serialInfo.dataBits);
        serialPort.setNumStopBits(serialInfo.stopBits);
        serialPort.setParity(serialInfo.parity);

        int timeoutMode = SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING;
        serialPort.setComPortTimeouts(timeoutMode, TIMEOUT, TIMEOUT);

        if (!serialPort.openPort()) {
            log.error("Failed to open serial port");
            return;
        }

        // 在生产环境注册固定中断监听器
        if (!isTestMode) {
            registerFixedListener();
            log.info("Fixed interrupt listener registered for port: " + serialInfo.portName);
        } else {
            log.info("Test mode detected, skipping interrupt listener registration");
        }
    }

    
    /**
     * @deprecated 此方法不应被调用，无法完成资源释放，更换正确方法
     * @see #closePort(String identity)
     */
    @Deprecated
    public void closePort(){

    }

    public void closePort(String identity) {
        // Check if identity exists in registered integrations
        if (!registeredIntegrations.contains(identity)) {
            throw new IllegalArgumentException("Identity not found: " + identity);
        }

        // Remove the integration from registered list
        registeredIntegrations.remove(identity);

        // Only close port if no integrations are registered
        if (registeredIntegrations.isEmpty() && serialPort.isOpen()) {
            // Clean up the port-specific thread pool
            SerialTimeoutScheduler.cleanupScheduler(getPortName());

            serialPort.closePort();
            log.info("Serial port closed by " + identity);
        } else {
            log.info("Identity removed but port kept open: " + identity +
                              ", remaining integrations: " + registeredIntegrations.size());
        }
    }

    public boolean isPortOpen() {
        return serialPort.isOpen();
    }

    public String getSystemPortName() {
        return serialPort.getSystemPortName();
    }

    /**
     * 获取端口名称
     * @return 端口名称
     * @throws IllegalStateException 如果端口名称无法获取
     */
    public String getPortName() {
        if (serialInfo != null && serialInfo.portName != null) {
            String port = serialInfo.portName;
            if (!port.trim().isEmpty()) {
                return port;
            }
        }
        throw new IllegalStateException("Unable to get port name from SerialSource");
    }

    /**
     * 获取底层 SerialPort 对象
     * @return SerialPort 对象
     */
    public SerialPort getSerialPort() {
        return serialPort;
    }

    /**
     * 关闭串口并清理所有资源
     * 这个方法会关闭串口并清理相关的线程池和监听器
     */
    public void close() {
        try {
            // 移除所有监听器
            if (dataListeners != null) {
                removeAllDataListeners();
            }

            // 清理端口相关的线程池
            if (serialInfo != null && serialInfo.portName != null) {
                SerialTimeoutScheduler.cleanupScheduler(serialInfo.portName);
            }

            // 关闭串口
            if (serialPort != null && serialPort.isOpen()) {
                serialPort.closePort();
                log.info("SerialSource closed for port: {}",
                        serialInfo != null ? serialInfo.portName : "unknown");
            }
        } catch (Exception e) {
            log.warn("Error closing SerialSource: {}", e.getMessage());
        }
    }

    /**
     * 检查串口是否已关闭
     * @return 如果串口已关闭返回true
     */
    public boolean isClosed() {
        return serialPort == null || !serialPort.isOpen();
    }

    // ========== 中断驱动相关方法 ==========

    /**
     * 添加数据监听器
     *
     * @param listener 监听器
     */
    public void addDataListener(SerialDataListener listener) {
        if (listener != null && !dataListeners.contains(listener)) {
            dataListeners.add(listener);
            log.debug(this.getPortName() + " added data listener, total listeners: " + dataListeners.size());
            // 如果发送命令后已经收到了消息，则认为是本次命令的应答，直接将数据发布给监听器，确保不丢失数据
            if (continuousReceiveBuffer.size() > 0) {
                byte[] buffer;
                bufferLock.lock();
                try {
                    buffer = continuousReceiveBuffer.readAndClear();
                } finally {
                    bufferLock.unlock();
                }
                if(buffer != null && buffer.length > 0) {
                    listener.onDataReceived(buffer, buffer.length);
                }
            }
        }
    }

    /**
     * 移除数据监听器
     *
     * @param listener 监听器
     */
    public void removeDataListener(SerialDataListener listener) {
        if (listener != null && dataListeners.remove(listener)) {
            log.debug(this.getPortName() + " removed data listener, total listeners: " + dataListeners.size());
        }
    }

    /**
     * 移除所有数据监听器
     */
    public void removeAllDataListeners() {
        int count = dataListeners.size();
        dataListeners.clear();
        log.info(this.getPortName() + " removed all data listeners, count: " + count);
    }

    /**
     * 获取当前数据监听器数量
     *
     * @return 监听器数量
     */
    public int getDataListenerCount() {
        return dataListeners.size();
    }

    /**
     * 清空接收缓冲区
     */
    private void clearReceiveBuffer() {
        bufferLock.lock();
        try {
            continuousReceiveBuffer.clear();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * 从缓冲区读取字节数组（中断模式使用）
     *
     * @return 读取的字节数组
     */
    private CompletableFuture<byte[]> readFromBufferBytes() {

        return CompletableFuture.supplyAsync(() -> {
            bufferLock.lock();
            try {
                // 直接返回字节数组，无需转换
                return continuousReceiveBuffer.readAndClear();
            } finally {
                bufferLock.unlock();
            }
        }, SerialAsyncExecutor.getExecutor());
    }

    /**
     * 注册固定中断监听器
     */
    private void registerFixedListener() {
        if (serialPort == null || !serialPort.isOpen()) {
            log.warn(this.getPortName() + " cannot register listener: port not open");
            return;
        }

        eventAdapter = new SerialSourceEventAdapter(this);

        // 添加一个内部监听器来维护缓冲区
        eventAdapter.addListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                handleIncomingData(data, length);
            }

            @Override
            public void onError(Exception ex) {
                log.warn("Error in fixed listener: " + ex.getMessage());
            }
        });

        // 注册到串口
        serialPort.addDataListener(eventAdapter);
    }

    /**
     * 处理接收到的数据
     *
     * @param data 接收到的数据
     * @param length 数据长度
     */
    private void handleIncomingData(byte[] data, int length) {
        bufferLock.lock();
        try {
            // 直接追加字节数组，无需转换
            continuousReceiveBuffer.append(data, 0, length);
        } finally {
            bufferLock.unlock();
        }

        // 通知所有注册的监听器
        for (SerialDataListener listener : dataListeners) {
            try {
                listener.onDataReceived(data, length);
            } catch (Exception e) {
                log.warn(this.getPortName() + " error notifying listener: " + e.getMessage());
                listener.onError(e);
            }
        }
    }

    /**
     * 检测是否在测试环境
     *
     * @return true 如果在测试环境
     */
    private boolean detectTestEnvironment() {
        // 1. 检查堆栈中是否包含测试框架
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.contains("junit") ||
                className.contains("mockito") ||
                className.contains("test") ||
                className.contains("hamcrest")) {
                return true;
            }
        }

        // 2. 检查系统属性
        String testMode = System.getProperty("test.mode", "false");
        if (Boolean.parseBoolean(testMode)) {
            return true;
        }

        // 3. 检查是否为 Mock（这个检测在实际创建对象时可能不适用）
        // 留给 DefaultResponseHandlerStrategy 进行更精确的检测

        return false;
    }

    /**
     * 获取当前是否为测试模式
     *
     * @return true 如果为测试模式
     */
    public boolean isTestMode() {
        return isTestMode;
    }
}
