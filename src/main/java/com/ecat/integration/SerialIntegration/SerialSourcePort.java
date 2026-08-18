package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;

/**
 * SerialSourcePort manages the underlying serial port resource.
 * It is shared by multiple SerialSource instances that connect to the same port.
 * Package-private — not exposed to external callers.
 *
 * @author coffee
 */
public class SerialSourcePort {
    private static final Log log = LogFactory.getLogger(SerialSourcePort.class);
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final int maxWaiters;
    private String currentKey;
    private volatile long lockAcquireTime;
    private volatile String lockAcquireThread;
    private final Queue<String> waitQueue = new LinkedList<>();

    SerialPort serialPort;
    SerialInfo serialInfo;
    private final SerialIntegration integration;

    // Receive-path fields（P1 后：事件驱动 → 轮询；103000 后由自持 serial-io-sweeper 承载，见 SerialPollScheduler）
    private final DynamicByteArrayBuffer continuousReceiveBuffer = new DynamicByteArrayBuffer(1024, 2.0f);
    private final Lock bufferLock = new ReentrantLock();
    /** 轮询任务句柄；null = 无任务（未开/已停/测试模式不注册）。startPolling/stopPolling 在 pollLock 下读写。 */
    private ScheduledFuture<?> pollTask;
    private final Object pollLock = new Object();
    /** Modbus 等直持 InputStream 期间置 true：轮询任务跳过读取，数据留给直接流消费（旧事件适配器 pause 语义平移）。 */
    private volatile boolean pollPaused = false;
    private boolean isTestMode = false;

    // Connected SerialSource instances
    private final List<SerialSource> connectedSources = new CopyOnWriteArrayList<>();

    /**
     * Package-private constructor. Only SerialIntegration should create instances.
     */
    SerialSourcePort(SerialInfo serialInfo, int maxWaiters, SerialIntegration integration) {
        this.maxWaiters = maxWaiters;
        this.serialInfo = serialInfo;
        this.integration = integration;
        this.isTestMode = detectTestEnvironment();
    }

    // ========== Source management ==========

    void registerSource(SerialSource source) {
        if (!connectedSources.contains(source)) {
            connectedSources.add(source);
            log.info("[OPEN] port={}, identity={}, total sources={}, sources={}",
                    getPortName(), source.getIdentity(), connectedSources.size(), formatIdentities());
        }
        openPort(source.getIdentity());
    }

    void unregisterSource(SerialSource source) {
        if (connectedSources.remove(source)) {
            log.info("[CLOSE] port={}, identity={}, remaining sources={}, sources={}",
                    getPortName(), source.getIdentity(), connectedSources.size(), formatIdentities());
            if (connectedSources.isEmpty()) {
                // Last source disconnected — close port and remove from integration map
                stopPolling();
                if (serialPort != null && serialPort.isOpen()) {
                    serialPort.closePort();
                    log.info("[CLOSED] port={}, last source removed by identity={}", getPortName(), source.getIdentity());
                }
                if (integration != null) {
                    integration.removePort(getPortName());
                }
            }
        }
    }

    /**
     * 格式化当前连接的 identity 列表，用于日志输出
     */
    private String formatIdentities() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (SerialSource s : connectedSources) {
            if (!first) sb.append(", ");
            sb.append(s.getIdentity());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    List<SerialSource> getConnectedSources() {
        return connectedSources;
    }

    // ========== Lock management ==========

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    String acquire() {
        return acquire(5, TimeUnit.SECONDS);
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时、超出等待队列容量，
     *         或唤醒后锁已被快速路径请求抢占（handed-off race 失败，按未取得总线重试）
     */
    String acquire(long timeout, TimeUnit unit) {
        String requestKey = generateRequestKey();
        lock.lock();
        try {
            if (currentKey == null) {
                currentKey = requestKey;
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                return requestKey;
            } else {
                if (waitQueue.size() < maxWaiters) {
                    waitQueue.add(requestKey);
                    boolean isAwoken = false;
                    try {
                        isAwoken = condition.await(timeout, unit);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Wait interrupted: " + requestKey);
                        waitQueue.remove(requestKey);
                        return null;
                    }

                    if (isAwoken) {
                        // 接管锁必须同时满足：自己是队头 且 锁确实空闲（currentKey==null 复查不可省：
                        // release() 发出 signal 之后、等待者重入临界区之前，另一请求可经快速路径
                        // （currentKey==null 分支）抢先持有；若仍执行 currentKey = requestKey 会无声
                        // 覆盖其持有权，两个 key 同时自认持锁，RS485 半双工总线上并发收发即帧碰撞。
                        if (currentKey == null && waitQueue.peek() != null && waitQueue.peek().equals(requestKey)) {
                            currentKey = requestKey;
                            waitQueue.poll();
                            lockAcquireTime = System.currentTimeMillis();
                            lockAcquireThread = Thread.currentThread().getName();
                            return requestKey;
                        }
                        // 队头不是自己（队列变更，skip），或锁已被快速路径抢占：返回 null 前必须摘除自身
                        // key。若残留队头即成死 key——此后每次 signal 唤醒的等待者都因队头不匹配被
                        // 错误拒绝并同样泄漏，队列只增不减直至 maxWaiters 名额被尸体耗尽。
                        waitQueue.remove(requestKey);
                        return null;
                    } else {
                        waitQueue.remove(requestKey);
                        log.warn("Acquire timeout: {}, lock currently held by: {} (acquired at {} by thread {}), waitQueue size: {}",
                                requestKey, currentKey, lockAcquireTime, lockAcquireThread, waitQueue.size());
                        return null;
                    }
                } else {
                    log.warn("Max waiters exceeded, request rejected: " + requestKey);
                    return null;
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
    boolean release(String releaseKey) {
        lock.lock();
        try {
            if (currentKey != null && currentKey.equals(releaseKey)) {
                currentKey = null;
                lockAcquireTime = 0;
                lockAcquireThread = null;
                if (!waitQueue.isEmpty()) {
                    condition.signal();
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

    // ========== Port management ==========

    private void openPort(String identity) {
        if (serialPort != null && serialPort.isOpen()) {
            log.info("[OPENED] port={}, already opened (requested by identity={})", serialInfo.portName, identity);
            return;
        }
        serialPort = SerialPort.getCommPort(serialInfo.portName);
        serialPort.setBaudRate(serialInfo.baudrate);
        serialPort.setNumDataBits(serialInfo.dataBits);
        serialPort.setNumStopBits(serialInfo.stopBits);
        serialPort.setParity(serialInfo.parity);
        serialPort.setFlowControl(serialInfo.flowControl);

        // 非阻塞写升级（§H.2.2）：jSerialComm 的 read/write 超时模式共享同一 fd 的 O_NONBLOCK 标志，
        // 是「全有或全无」（§B.2），无法只把 write 设非阻塞而保持 read 阻塞。故整端口改 TIMEOUT_NONBLOCKING。
        // 读路径是事件驱动（DATA_AVAILABLE → continuousReceiveBuffer），不依赖阻塞读（§B.8 实证安全）；
        // write 反压时返回 -1 并被 jSerialComm 关闭端口（§B.6），由 asyncSendData 显式处理（见下）。
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!serialPort.openPort()) {
            log.error("[OPEN FAILED] port={}, identity={}, baudrate={}, dataBits={}, stopBits={}, parity={}",
                    serialInfo.portName, identity, serialInfo.baudrate, serialInfo.dataBits, serialInfo.stopBits, serialInfo.parity);
            return;
        }

        if (!isTestMode) {
            startPolling();
            log.info("[OPENED] port={}, identity={}, baudrate={}, dataBits={}, stopBits={}, parity={}",
                    serialInfo.portName, identity, serialInfo.baudrate, serialInfo.dataBits, serialInfo.stopBits, serialInfo.parity);
        } else {
            log.info("[OPENED] port={}, identity={}, test mode, baudrate={}",
                    serialInfo.portName, identity, serialInfo.baudrate);
        }
    }

    boolean isPortOpen() {
        return serialPort.isOpen();
    }

    public SerialPort getSerialPort() {
        return serialPort;
    }

    String getSystemPortName() {
        return serialPort.getSystemPortName();
    }

    public String getPortName() {
        if (serialInfo != null && serialInfo.portName != null) {
            String port = serialInfo.portName;
            if (!port.trim().isEmpty()) {
                return port;
            }
        }
        throw new IllegalStateException("Unable to get port name from SerialSourcePort");
    }

    /**
     * 获取当前串口超时设置（毫秒）
     */
    public int getTimeout() {
        return serialInfo.timeout;
    }

    boolean isTestMode() {
        return isTestMode;
    }

    // ========== Buffer management ==========

    /**
     * 清空接收缓冲区
     */
    void clearReceiveBuffer() {
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
    CompletableFuture<byte[]> readFromBufferBytes() {
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

    // ========== Port read polling（P1：jSerialComm 事件线程 → 共享调度器轮询） ==========

    /**
     * 轮询周期（毫秒），对齐 IO 线程收敛调研建议（≤50ms，modbus4j InputStreamListener 同量级）。
     * 生效粒度：生产 sweeper 与测试注入的 STPE 均为真实 25ms（103000 后端口轮询已撤出
     * 引擎表轮——引擎 tick 100ms 的取整粒度不再适用于本任务）。
     */
    static final long POLL_PERIOD_MS = 25L;

    /**
     * 启动端口读轮询（openPort 成功后调用；测试模式不调用，沿用旧 isTestMode 边界）。
     * 先取消既有任务再挂新任务——写反压自动重开、并发注册等场景下保证单口单任务，
     * 不会出现双任务同口双读。与 {@link #stopPolling()} 在 pollLock 下串行化。
     */
    void startPolling() {
        synchronized (pollLock) {
            stopPolling();
            if (serialPort == null || !serialPort.isOpen()) {
                log.warn("{} cannot start polling: port not open", getPortName());
                return;
            }
            SerialPortPollTask task = new SerialPortPollTask(this);
            ScheduledFuture<?> handle = SerialPollScheduler.delegate()
                    .scheduleWithFixedDelay(task, POLL_PERIOD_MS, POLL_PERIOD_MS, TimeUnit.MILLISECONDS);
            task.bindHandle(handle);
            pollTask = handle;
            log.info("[POLL-START] port={}, period={}ms, scheduler={}",
                    getPortName(), POLL_PERIOD_MS, SerialPollScheduler.describe());
        }
    }

    /**
     * 停止端口读轮询（最后一个 source 注销关闭端口时调用；幂等）。
     * 口被写反压/jSerialComm 自动关闭的场景由 {@link SerialPortPollTask} 的 -1 哨兵自取消兜住，
     * 两条停止路径任一先到即停。
     */
    private void stopPolling() {
        synchronized (pollLock) {
            ScheduledFuture<?> handle = pollTask;
            pollTask = null;
            if (handle != null) {
                handle.cancel(false);
            }
        }
    }

    /** 当前轮询任务句柄（生命周期断言用；无任务为 null）。 */
    ScheduledFuture<?> getPollTaskHandle() {
        synchronized (pollLock) {
            return pollTask;
        }
    }

    /** Modbus 直持 InputStream 期间轮询任务是否让路。 */
    boolean isPollPaused() {
        return pollPaused;
    }

    /**
     * 处理接收到的数据（轮询任务在读到字节后调用；与旧事件路径同一入口）
     *
     * @param data 接收到的数据
     * @param length 数据长度
     */
    void handleIncomingData(byte[] data, int length) {
        // 通讯帧捕获：读路径唯一入口（轮询与事件路径共用），独立数据面零 logback 开销
        CommTraceBuffer.instance().rx(CommTraceTransport.SERIAL, serialInfo.portName, data, length, null, null);
        bufferLock.lock();
        try {
            // 直接追加字节数组，无需转换
            continuousReceiveBuffer.append(data, 0, length);
        } finally {
            bufferLock.unlock();
        }

        // Notify all connected SerialSource instances
        notifyDataReceived(data, length);
    }

    void notifyDataReceived(byte[] data, int length) {
        for (SerialSource source : connectedSources) {
            try {
                source.notifyListeners(data, length);
            } catch (Exception e) {
                log.warn(getPortName() + " error notifying source: " + e.getMessage());
            }
        }
    }

    /**
     * 暂停端口读轮询，阻止轮询任务从串口读取数据。
     * 当 Modbus 等需要直接 InputStream/OutputStream 访问串口时调用，
     * 避免轮询读取与 direct stream 竞争数据（旧事件适配器 pause 语义的轮询版平移，
     * 方法名保留——SerialSource 公共 API，Modbus 侧消费方零改动）。
     */
    void pauseEventAdapter() {
        pollPaused = true;
        log.info("[POLL-PAUSED] port={}", getPortName());
    }

    /**
     * 恢复端口读轮询。
     * 当 Modbus 释放直接串口访问后调用。
     */
    void resumeEventAdapter() {
        pollPaused = false;
        log.info("[POLL-RESUMED] port={}", getPortName());
    }

    void deliverBufferedData(SerialDataListener listener) {
        if (continuousReceiveBuffer.size() > 0) {
            byte[] buffer;
            bufferLock.lock();
            try {
                buffer = continuousReceiveBuffer.readAndClear();
            } finally {
                bufferLock.unlock();
            }
            if (buffer != null && buffer.length > 0) {
                listener.onDataReceived(buffer, buffer.length);
            }
        }
    }

    // ========== I/O operations ==========

    CompletableFuture<Boolean> asyncSendData(byte[] bytes) {
        return CompletableFuture.runAsync(() -> {
            // 【改动②-1 写前自动 reopen】非阻塞 write 反压会关闭端口（§B.6），下次写前若端口已关则重开，
            // 避免依赖外部干预即恢复通信；重开仍失败说明端口不可用（如 ttyUSB 拔线），抛 SerialWriteException 明确错误。
            // 无 isTestMode guard：单测均 stub 掉 asyncSendData（真实方法体不执行），真端口功能测试对端都在（-1 不触发），
            // 故 reopen/-1 检查无条件执行即可（§H.2.5 定稿）。
            if (serialPort == null || !serialPort.isOpen()) {
                log.warn("[WRITE-REOPEN] port={}, 端口未开, 写前自动重开", serialInfo.portName);
                openPort("[auto-reopen]");
                if (serialPort == null || !serialPort.isOpen()) {
                    log.warn("[WRITE-REOPEN-FAIL] port={}, 自动重开失败, 写路径抛 SerialWriteException", serialInfo.portName);
                    throw new SerialWriteException("串口重开失败(端口不可用): port=" + serialInfo.portName);
                }
            }
            // 写前清缓冲（原有逻辑保留）
            if (isTestMode) {
                while (serialPort.bytesAvailable() > 0) {
                    serialPort.readBytes(new byte[serialPort.bytesAvailable()], serialPort.bytesAvailable());
                }
            } else {
                clearReceiveBuffer();
            }
            // 【改动③ / B5 根因修复】非阻塞 write 返回 <0 表示反压（端口已被 jSerialComm 关闭）。
            // 旧代码丢弃 writeBytes 返回值（静默成功，asyncSendData 永远返 true），现在显式检查并抛出——
            // 把「发不出去」如实告诉调用方，而非伪装成功。
            int written = serialPort.writeBytes(bytes, bytes.length);
            // 通讯帧捕获：写路径唯一出口；写失败只累计通道错误计数（不产生 TX 帧，如实）
            if (written >= 0) {
                CommTraceBuffer.instance().tx(CommTraceTransport.SERIAL, serialInfo.portName, bytes, null);
            } else {
                CommTraceBuffer.instance().error(CommTraceTransport.SERIAL, serialInfo.portName);
            }
            if (written < 0) {
                log.warn("[WRITE-FAIL] port={}, writeBytes 返 {} (非阻塞反压, 端口已被关闭), 写路径抛 SerialWriteException",
                        serialInfo.portName, written);
                throw new SerialWriteException("串口写入失败(writeBytes 返 " + written + ", 端口已被反压关闭): port=" + serialInfo.portName);
            }
        }, SerialAsyncExecutor.getExecutor()).thenApplyAsync(v -> {
            return true;
        }, SerialAsyncExecutor.getExecutor()).exceptionally(ex -> {
            // 【改动②-2 类型透传】SerialWriteException 原样向上抛（保留写失败语义供调用方识别），
            // 其余异常仍包成通用 RuntimeException（保持旧行为兼容）。
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof SerialWriteException) {
                throw (SerialWriteException) cause;
            }
            throw new RuntimeException("Failed to send data: " + cause.getMessage(), cause);
        });
    }

    CompletableFuture<byte[]> asyncReadDataBytes() {
        if (isTestMode) {
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
            return readFromBufferBytes();
        }
    }

    boolean isClosed() {
        return serialPort == null || !serialPort.isOpen();
    }

    // ========== Test environment detection ==========

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
}
