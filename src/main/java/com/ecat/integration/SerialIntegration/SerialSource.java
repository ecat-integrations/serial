package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceOwner;
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
     * 本视图的权威归属 owner（io-resource-owner §5.2）：register(Info, owner/host) 注册携带。
     * null = identity 串直接构造的视图（测试基建形态，锁归因回落线程 MDC/回填现状）。
     */
    private volatile ResourceOwner owner;

    /**
     * 获取此 SerialSource 的标识符
     */
    String getIdentity() {
        return identity;
    }

    /** 本视图登记的 owner（账本折叠/精准查询读面用）；LEGACY 注册（identity 串形态）为 null。 */
    ResourceOwner getOwner() {
        return owner;
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
     * 带权威归属的视图构造（io-resource-owner §5.2）：register(Info, owner/host) 与
     * registerForDevice 共用；identity 由 owner 派生（见 {@link #identityOf}）。
     */
    SerialSource(SerialSourcePort sourcePort, ResourceOwner owner) {
        this.sourcePort = sourcePort;
        this.identity = identityOf(owner);
        this.owner = owner;
        sourcePort.registerSource(this);
        log.info("SerialSource created for port: " + getPortName() + ", identity: " + identity);
    }

    /**
     * identity 串由 owner 派生（§5.2：identity 不再由调用方自由拼）：分层拼接稳定确定——
     * 同设备重注册必得同串（引用计数/日志键不变量保持）；LEGACY 原样保留 rawIdentity
     * （迁移期日志可 grep 原串，定位未迁移调用方）。
     */
    static String identityOf(ResourceOwner owner) {
        switch (owner.getLevel()) {
            case DEVICE:
                return "DEVICE:" + owner.getCoordinate() + ":" + owner.getEntryId() + ":" + owner.getDeviceId();
            case ENTRY:
                return "ENTRY:" + owner.getCoordinate() + ":" + owner.getEntryId();
            case INTEGRATION:
                return "INTEGRATION:" + owner.getCoordinate();
            case LEGACY:
                return owner.getRawIdentity();
            default:
                throw new IllegalStateException("未覆盖的 owner 层级: " + owner.getLevel());
        }
    }

    /**
     * 本视图取锁时注入的权威归属（调用方无感，acquire/acquirePollingBounded 委托时自动携带）：
     * LEGACY 不参与锁注入——W2 契约下 owner 参数会整组压制线程 MDC 归因，LEGACY 无
     * 设备身份字段，注入反而丢掉线程 MDC 归属（identity 串直接构造的视图维持此现状）。
     */
    ResourceOwner lockOwner() {
        ResourceOwner current = owner;
        return current != null && current.getLevel() != OwnerLevel.LEGACY ? current : null;
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
     * 尝试获取锁，支持等待队列。视图已登记权威归属时自动注入（{@link #lockOwner()}，
     * 调用方无感）——授予后 TX/RX 捕获点同读该归属作 CommTrace 权威参数。
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    public String acquire() {
        return sourcePort.acquire(lockOwner());
    }

    /**
     * 尝试获取锁，支持等待队列和超时（owner 注入同 {@link #acquire()}）
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取/唤醒或进入等待），null表示超时或超出等待队列容量
     */
    public String acquire(long timeout, TimeUnit unit) {
        return sourcePort.acquire(timeout, unit, lockOwner());
    }

    /**
     * 轮询锁获取契约入口（20260913-073600 方案 c，与 modbus {@code ModbusSource}
     * 同型）：无竞争快路径直达；锁忙时 FIFO 有界等待移交 IO 旁池线程——调用线程零 park。
     * 视图已登记权威归属时自动注入（{@link #lockOwner()}，调用方无感）。
     * 语义与记账见 {@link SerialSourcePort#acquirePollingBounded(ResourceOwner, long)}。
     *
     * @param budgetMs 锁等待预算（毫秒，须 &gt; 0；SDK 侧按轮询周期 clamp）
     * @return 锁标识 CF；null 完成=本周期弃轮（真弃轮在源侧记账）
     */
    public CompletableFuture<String> acquirePollingBounded(long budgetMs) {
        return sourcePort.acquirePollingBounded(lockOwner(), budgetMs);
    }

    /** 本端口累计轮询真弃轮次数（有界等待预算耗尽的计数，{@code acquirePollingBounded} 记账）。 */
    public long getLockBusySkipCount() {
        return sourcePort.getLockBusySkipCount();
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
     * 本端口 IO 执行域（域自持 {@link SerialIoPool} 的 per-port 串行视图：同口 FIFO 串行、
     * 异口并行）。串口收尾链（响应处理/事务 release 等）统一经此执行域提交——29 号 v2 S1
     * 起脱离引擎车道，替代已退役的全局 serial-async 单 gate（E1 事故载体）与引擎车道视图。
     */
    public ExecutorService getIoExecutor() {
        return sourcePort.ioExecutor();
    }

    /**
     * IO 线程（sweeper/监听器回调）投递已定界的完整入站帧到域池端口串行视图（P1：
     * IO 线程只读字节+组帧+投递，设备业务 finalize 在 ecat-serial-io-N 上执行）。
     * O(1) 入队即返；域池拒绝由 dispatcher 记账，调用方不重试（响应超时兜底）。
     * 监听器（Listener 包）与响应处理策略消费；设备集成零改码。
     */
    public void submitInboundFrame(byte[] frameBytes, Runnable finalizeBody) {
        sourcePort.submitInboundFrame(frameBytes, finalizeBody);
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
     * 挂死端口自愈（Q-1/A2）：close + reopen 强拆挂死的本地阻塞写，语义见
     * {@link SerialSourcePort#recoverWedgedPort(String)}。由事务级硬超时路径调用
     * （SerialTransactionStrategy），也可供 modbus RTU 等直持串口流的集成在其事务
     * 硬超时路径上复用（挂死的 modbus 写发生在同一串口 fd 上）。
     *
     * @param reason 触发原因（日志定位用）
     */
    public void recoverWedgedPort(String reason) {
        sourcePort.recoverWedgedPort(reason);
    }

    /**
     * 标记当前事务已被硬超时强拆（F-43 清洗①）：端口残留写闸门生效，被掐事务代内的后续
     * asyncSendData 提交（delay 定时器到点后的补发命令）一律拒绝，堵与新事务交错的复活路径
     * （bugs/bug-record-20260826-093000 Q1）。由事务级硬超时路径调用
     * （SerialTransactionStrategy），下一次 acquire 授予新代后自动放行。
     */
    public void markTransactionAborted() {
        sourcePort.markTransactionAborted();
    }

    /**
     * 暂停事件适配器，阻止其从串口读取数据。
     * Modbus 等需要直接 InputStream/OutputStream 访问串口时调用。
     */
    public void pauseEventAdapter() {
        sourcePort.pauseEventAdapter();
    }

    /**
     * 恢复事件适配器，重新注册到串口。
     * Modbus 释放直接串口访问后调用。
     */
    public void resumeEventAdapter() {
        sourcePort.resumeEventAdapter();
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
     * 被动接收统一注册形态（17 号 v2.1 §2.2 接收模式薄包装）：以
     * {@code Consumer<byte[]>} 注册被动帧处理，内部适配到既有
     * {@link SerialDataListener} 体系（对象池/回放窗/挂起缓冲等机制原样继承，
     * 不另造监听通道）。serial 从机/被动设备仓（biaoqi 形态）的注册词汇。
     *
     * <p>回调线程契约：onDataReceived 的既有调用线程（serial-io-sweeper 数据面线程），
     * 消费方须轻量（组帧/转交，禁阻塞——同 B5 纪律）；需要业务处理时经
     * {@link #submitInboundFrame(byte[], Runnable)} 转域池端口视图。适配器内异常由
     * 既有 notifyListeners 兜住转 {@code onError}（本包装记 warn，不打断其他监听器）。
     *
     * @param frameHandler 每次收到的字节切片消费者（恰好 length 字节：短于底层数组时
     *                     防御性拷贝切片，等长时零拷贝直传——读路径每读新分配，无复用竞争）
     * @return 适配后的监听器（注销时经 {@link #removeDataListener(SerialDataListener)}；
     *         设备注销场景由 {@link #closePort()} 全量清理覆盖）
     */
    public SerialDataListener onFrame(Consumer<byte[]> frameHandler) {
        if (frameHandler == null) {
            throw new IllegalArgumentException("frameHandler 不能为 null");
        }
        SerialDataListener adapter = new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                frameHandler.accept(length == data.length ? data : Arrays.copyOf(data, length));
            }

            @Override
            public void onError(Exception ex) {
                log.warn(getPortName() + " [" + identity + "] onFrame handler error: " + ex.getMessage());
            }
        };
        addDataListener(adapter);
        return adapter;
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
