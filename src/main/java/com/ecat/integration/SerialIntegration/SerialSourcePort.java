package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.CommTrace.ResourceOwner;

/**
 * SerialSourcePort manages the underlying serial port resource.
 * It is shared by multiple SerialSource instances that connect to the same port.
 * Package-private — not exposed to external callers.
 *
 * <p><b>仲裁与执行（serial IO 最小模型）</b>：本类是端口物理资源（fd、接收缓冲、轮询
 * 句柄）的持有者；同口仲裁与 IO 排序全部委派 {@link SerialPortExecutor}——每口一个
 * 串行执行者，状态机 IDLE→AWAIT_WRITE→AWAIT_RESPONSE→IDLE（旁路 DRAINING/HELD/
 * RECOVERING/CLOSED），一场对话从写出首字节起独占端口。旧九套锁机制（端口锁+准入命令
 * 队列+授予回执、同线程嵌套守卫、幽灵锁收割、事务代数闸门、2×超时回放窗、bufferLock、
 * pollLock、lifecycleLock+retired 门）全部物理删除：严格串行 + 异常结束排空使其防护的
 * 事故形态结构性消失。fd 的开与关只发生在口执行线程与硬超时看门狗（跨线程 closePort
 * 强拆挂死写）——句柄竞态无处发生。
 *
 * <p><b>锁入口使用规则（SDK 调度纪律）</b>：本类的 acquire/acquirePollingBounded 只应有
 * {@link SerialTransactionStrategy} 的两个事务入口（及 serial-tcp-server 网关这类自管
 * 收发时序的框架型消费方）调用，集成设备代码不应直接取锁。两个入口是两种调度纪律而非冗余：
 * <ul>
 *   <li>命令/写事务 → {@code executeWithLambda}：高优先级对话入队（队满同步抛
 *       {@link PortQueueFullException}），等锁预算内未授予则失败——命令的时效语义是
 *       「最终要执行」，有限等待是正确的；</li>
 *   <li>周期轮询 → {@code executePolling}：普通优先级对话入队——与写命令同队同公平，
 *       排队等待预算（周期÷4 clamp[200,500]ms）耗尽才弃本轮（「过期即弃」保留在预算
 *       边界上）。</li>
 * </ul>
 * round/事务临界体内需要追加直发命令时，使用 SDK 注入的 source 直接 send（此时本对话
 * 在飞，写请求在口执行线程与其余 IO 串行），勿再经事务入口二次入队——同线程嵌套提交
 * 会排队到自身之后，等待自身完成即自死锁。
 *
 * @author coffee
 */
public class SerialSourcePort {
    private static final Log log = LogFactory.getLogger(SerialSourcePort.class);

    SerialPort serialPort;
    volatile SerialInfo serialInfo;
    private final SerialIntegration integration;
    private final SerialPortExecutor executor;

    // Receive-path fields（RX 缓冲归口执行线程私有：仅口执行线程读写，无锁）
    private final DynamicByteArrayBuffer continuousReceiveBuffer = new DynamicByteArrayBuffer(1024, 2.0f);
    /** 轮询任务句柄；null = 无任务（未开/已停/测试模式不注册）。开/停只发生在口执行线程。 */
    private volatile ScheduledFuture<?> pollTask;
    /** Modbus 等直持 InputStream 期间置 true：轮询任务跳过读取，数据留给直接流消费（旧事件适配器 pause 语义平移）。 */
    private volatile boolean pollPaused = false;
    private boolean isTestMode = false;

    // Connected SerialSource instances
    private final List<SerialSource> connectedSources = new CopyOnWriteArrayList<>();

    /**
     * Package-private constructor. Only SerialIntegration should create instances.
     */
    SerialSourcePort(SerialInfo serialInfo, int maxWaiters, SerialIntegration integration) {
        this.serialInfo = serialInfo;
        this.integration = integration;
        this.executor = new SerialPortExecutor(this, serialInfo.portName, maxWaiters);
        this.isTestMode = detectTestEnvironment();
    }

    SerialPortExecutor executor() {
        return executor;
    }

    // ========== Source management ==========

    void registerSource(SerialSource source) {
        if (!connectedSources.contains(source)) {
            connectedSources.add(source);
            log.info("[OPEN] port={}, identity={}, total sources={}, sources={}",
                    getPortName(), source.getIdentity(), connectedSources.size(), formatIdentities());
        }
        // fd 重开检查经执行者事件到口执行线程（句柄开/关只在口执行线程发生）
        executor.sessionRegistered(source.getIdentity());
    }

    void unregisterSource(SerialSource source) {
        if (connectedSources.remove(source)) {
            log.info("[CLOSE] port={}, identity={}, remaining sources={}, sources={}",
                    getPortName(), source.getIdentity(), connectedSources.size(), formatIdentities());
            if (connectedSources.isEmpty()) {
                // 最后一份注销 = 退役：地图摘除在调用方线程同步完成（closePort 返回后同口
                // 重注册必得全新端口对象，杜绝把新 source 挂到垂死对象上的半死挂靠）；
                // fd 关闭/撤轮询/未开始对话取消经执行者事件到口执行线程（CLOSED 终态）。
                if (integration != null) {
                    integration.removePort(getPortName());
                }
                executor.retire();
            } else {
                // 非最后一份：该 session 未开始的对话取消（closed 语义异常完成，§3.4）
                executor.sessionLeft(source);
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

    /**
     * 消费方一步得源的原子挂靠（io-resource-owner §9-10 registerForDevice 库内侧）：
     * 集成端解析（端口地图命中）与追加（connectedSources）之间设备可能恰好被摘——本方法
     * 复查终态（CLOSED）后挂靠，杜绝把消费方源挂到已拆除对象上的半死挂靠。
     * CLOSED = 设备已摘（本对象已除名），如实返回 null（未注册语义，不另开资源）。
     * 挂靠与退役的 TOCTOU 窗由口执行线程兜底：迟到注册的重开事件在 CLOSED 后被拒。
     */
    SerialSource attachSourceForDevice(ResourceOwner owner) {
        if (executor.state() == SerialPortExecutor.PortState.CLOSED) {
            return null;
        }
        return new SerialSource(this, owner);
    }

    // ========== HELD 对话（同步 acquire/release 的承载） ==========

    /**
     * 写路径等锁的默认 park 预算（秒）：命令/写事务「最终要执行」的有限等待语义
     * （{@link #acquire()} 无参入口取本值）。常量提取供契约测试零耗时断言——
     * 默认值漂移须可被立即发现。
     */
    static final long DEFAULT_ACQUIRE_WAIT_SECONDS = 5;

    /**
     * 同步取口（HELD 对话）：提交高优先级 HELD 对话并 park 在授予回执上，预算内被授予
     * 返回锁标识（此后持有者须 {@link #release(String)} 收尾；持有超时由执行者看门狗
     * 强制收场并排空——持有者线程死掉不会锁死口），超时/队列满返回 null。
     * 视图已登记权威归属时经 {@code owner} 注入（授予后在飞对话的 CommTrace 权威参数）。
     *
     * @return 锁标识（成功授予），null 表示超时或队列满
     */
    String acquire() {
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS, null);
    }

    /** {@link #acquire()} 的超时显式档。 */
    String acquire(long timeout, TimeUnit unit) {
        return acquire(timeout, unit, null);
    }

    /** 带权威归属的阻塞取口（视图入口形态）：授予点登记归属。 */
    String acquire(ResourceOwner owner) {
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS, owner, null);
    }

    /** 带权威归属、等待预算显式的阻塞取口（owner 注入、无 session 记账形态）。 */
    String acquire(long timeout, TimeUnit unit, ResourceOwner owner) {
        return acquire(timeout, unit, owner, null);
    }

    /** 视图入口形态（默认等待预算 + owner 注入 + session 记账）。 */
    String acquire(ResourceOwner owner, SerialSource session) {
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS, owner, session);
    }

    /**
     * 同步取口（HELD 对话，全参形态）：等待上限与持有上限均取 {@code timeout}
     * （等待自提交起、持有自授予起各自计时）。
     *
     * @return 锁标识（成功授予），null 表示超时或队列满
     */
    String acquire(long timeout, TimeUnit unit, ResourceOwner owner, SerialSource session) {
        long waitMs = Math.max(0L, unit.toMillis(timeout));
        SerialPortExecutor.Conversation conv = executor.trySubmitHeld(session, owner, waitMs, waitMs);
        if (conv == null) {
            return null;
        }
        try {
            return conv.granted.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return abandonHeldUnlessGranted(conv, "Acquire timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abandonHeldUnlessGranted(conv, "Wait interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            // 结构不可达：granted 只会被 complete(key) 或 cancel（取消走 CancellationException）
            // ——到达即状态机缺陷，如实上抛不吞
            throw new IllegalStateException("HELD grant future failed unexpectedly", e.getCause());
        } catch (java.util.concurrent.CancellationException e) {
            // 等待期内对话被取消（等待超时/退役取消）：授予未发生，返回 null
            return null;
        }
    }

    /**
     * 等待方超时/中断的离队或认领：已被授予（HELD 运行中）则授予不得丢失——返回 key
     * 交由调用方照常 release；仍在队则离队返回 null（不泄漏、不挡后续队头）。
     */
    private String abandonHeldUnlessGranted(SerialPortExecutor.Conversation conv, String reason) {
        String grantedKey = conv.granted.getNow(null);
        if (grantedKey != null) {
            return grantedKey;
        }
        executor.abandonQueued(conv, reason);
        return null;
    }

    /**
     * 释放口（HELD 对话手动收尾，非阻塞）：key 匹配当前 HELD 对话则投递收尾事件并返回
     * true（实际收场在口执行线程微秒级完成并开下一场）；不匹配（含持有超时已被看门狗
     * 收场的竞速）返回 false。
     *
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功
     */
    boolean release(String releaseKey) {
        return executor.requestRelease(releaseKey);
    }

    /** 本端口累计轮询真弃轮次数（队满被拒/挤出/等待预算耗尽的计数，运行时可观测用）。 */
    public long getLockBusySkipCount() {
        return executor.getLockBusySkipCount();
    }

    /** 排队对话数（高+普通，不含在飞；执行者队列深度观测缝，测试与诊断用）。 */
    int queuedConversations() {
        return executor.queuedCount();
    }

    /** 是否有占用口执行线程的在飞对话（HELD/排空/恢复都算；测试断言用）。 */
    boolean hasInFlightConversation() {
        return executor.hasInFlightConversation();
    }

    /** 当前在飞对话的权威归属（CommTrace TX/RX 捕获点读；无对话为 null）。 */
    ResourceOwner getLockAcquireOwner() {
        return executor.currentOwner();
    }

    /** 轮询面有界取口契约（无 session 记账形态：测试桥接与策略链路直调）。 */
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs) {
        return acquirePollingBounded(owner, budgetMs, null);
    }

    /** 轮询面有界取口契约（对外行为保持）：CF&lt;String&gt;，null 完成=本周期真弃轮。 */
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs, SerialSource session) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("acquirePollingBounded 要求 budgetMs > 0: " + budgetMs);
        }
        SerialPortExecutor.Conversation conv = executor.trySubmitHeld(session, owner, budgetMs, budgetMs);
        if (conv == null) {
            // 队满=本周期真弃轮（记账已由执行者完成），CF 以 null 完成（对外行为保持）
            return CompletableFuture.completedFuture(null);
        }
        // 等待超时以 granted 取消表达 → 映射回 null 完成（弃轮语义）
        return conv.granted.handle((String key, Throwable ex) -> ex != null ? null : key);
    }

    // ========== 事务提交（executeWithLambda/executePolling 入队化承载） ==========

    /**
     * 提交设备事务对话（SerialTransactionStrategy 两入口的承载）：队列满抛
     * {@link PortQueueFullException}（普通优先级由执行者记账后抛），视图已关闭抛
     * {@link SerialSourceClosedException}。
     */
    CompletableFuture<Boolean> submitTransaction(SerialSource session, ResourceOwner owner,
            SerialPortExecutor.Priority priority, long waitMs, long execTimeoutMs,
            Function<SerialSource, CompletableFuture<Boolean>> body) {
        return executor.submitBody(session, owner, priority, waitMs, execTimeoutMs, body).result;
    }

    // ========== I/O operations ==========

    /**
     * 异步写发送（IO 发起在口执行线程）：写请求经执行者事件到口执行线程执行——与对话
     * 迁移/排空/恢复同点串行，写前自动重开 + 写前清缓冲 + 反压检查 + CommTrace 帧捕获。
     * 排空/恢复/终态窗口的写入以 {@link SerialWriteException} 拒绝（残留补发防线）。
     */
    CompletableFuture<Boolean> asyncSendData(byte[] bytes) {
        return executor.submitWrite(null, bytes);
    }

    /**
     * requester-aware 发送入口：视图关闭在提交点拒绝；提交后、执行前关闭的排队写由
     * 口执行线程的 requester 终检拒绝。硬件写一旦开始则无法撤回——后续 close 只能
     * 拒绝更晚的新 IO，这是共享物理资源的不可回退边界。
     */
    CompletableFuture<Boolean> asyncSendData(SerialSource requester, byte[] bytes) {
        assertRequesterActive(requester, "asyncSendData");
        return executor.submitWrite(requester, bytes);
    }

    CompletableFuture<byte[]> asyncReadDataBytes() {
        if (isTestMode) {
            return readSerialPortDirectly();
        }
        return executor.submitBufferRead();
    }

    /**
     * requester-aware 读取入口：视图关闭在提交点拒绝（物理读与缓冲读都不再执行）。
     */
    CompletableFuture<byte[]> asyncReadDataBytes(SerialSource requester) {
        assertRequesterActive(requester, "asyncReadDataBytes");
        if (isTestMode) {
            return readSerialPortDirectly();
        }
        return executor.submitBufferRead();
    }

    /** 测试模式直读物理口（测试环境 mock 口在 ioExecutor 上读，与既有行为一致）。 */
    private CompletableFuture<byte[]> readSerialPortDirectly() {
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
        }, ioExecutor());
    }

    private void assertRequesterActive(SerialSource requester, String operation) {
        if (requester == null) {
            throw new IllegalArgumentException(operation + " requester 不能为 null");
        }
        if (requester.isViewClosed()) {
            throw new SerialSourceClosedException(operation, getPortName(), requester.getIdentity());
        }
    }

    boolean isClosed() {
        return serialPort == null || !serialPort.isOpen();
    }

    // ========== Port lifecycle（fd 开/关只在口执行线程与看门狗） ==========

    /**
     * 打开端口（口执行线程调用；终态拒绝）：物理参数全量设置 + 非阻塞超时模式 + 打开后
     * RX 清洗 + 启动读轮询（测试模式不挂轮询）。
     */
    void openPortNow(String identity) {
        if (executor.state() == SerialPortExecutor.PortState.CLOSED) {
            log.warn("[OPEN-REJECTED] port={}, identity={}, 已退役（最后一个 source 已注销），拒绝重开——"
                            + "本对象已从集成端口地图除名，任何 fd 都将成为孤儿",
                    serialInfo.portName, identity);
            return;
        }
        if (serialPort != null && serialPort.isOpen()) {
            log.info("[OPENED] port={}, already opened (requested by identity={})", serialInfo.portName, identity);
            return;
        }
        try {
            serialPort = SerialPort.getCommPort(serialInfo.portName);
            serialPort.setBaudRate(serialInfo.baudrate);
            serialPort.setNumDataBits(serialInfo.dataBits);
            serialPort.setNumStopBits(serialInfo.stopBits);
            serialPort.setParity(serialInfo.parity);
            serialPort.setFlowControl(serialInfo.flowControl);

            // 非阻塞写（§H.2.2）：jSerialComm 的 read/write 超时模式共享同一 fd 的 O_NONBLOCK 标志，
            // 是「全有或全无」（§B.2），无法只把 write 设非阻塞而保持 read 阻塞。故整端口改 TIMEOUT_NONBLOCKING。
            // 读路径是轮询驱动（数据到达 → 执行者帧事件），不依赖阻塞读；write 反压时返回 -1
            // 并被 jSerialComm 关闭端口（§B.6），由写路径显式处理（写前自动重开）。
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

            if (!serialPort.openPort()) {
                log.error("[OPEN FAILED] port={}, identity={}, baudrate={}, dataBits={}, stopBits={}, parity={}",
                        serialInfo.portName, identity, serialInfo.baudrate, serialInfo.dataBits, serialInfo.stopBits, serialInfo.parity);
                return;
            }
        } catch (RuntimeException openRejected) {
            // 口描述符无效（getCommPort 拒绝假口名/非法路径）：如实记账、端口保持关闭——
            // 与「口不存在时 openPort 返 false」同一终态；重开由写路径自动重开按需再试
            serialPort = null;
            log.error("[OPEN FAILED] port={}, identity={}, 口对象创建被拒（{}）",
                    serialInfo.portName, identity, openRejected.getMessage());
            return;
        }

        // 重开后 RX 清洗（F-43 清洗②）：清内核残留 + 应用层缓冲，重开后首读零旧字节
        drainAndClearReceiveBuffer("port-open");

        if (!isTestMode) {
            startPolling();
            log.info("[OPENED] port={}, identity={}, baudrate={}, dataBits={}, stopBits={}, parity={}",
                    serialInfo.portName, identity, serialInfo.baudrate, serialInfo.dataBits, serialInfo.stopBits, serialInfo.parity);
        } else {
            log.info("[OPENED] port={}, identity={}, test mode, baudrate={}",
                    serialInfo.portName, identity, serialInfo.baudrate);
        }
    }

    /** 关闭 fd（口执行线程与看门狗调用；jSerialComm closePort 线程安全）。 */
    void closeFd(String reason) {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            log.info("[CLOSED] port={}, fd closed ({})", serialInfo.portName, reason);
        }
    }

    boolean isFdOpen() {
        return serialPort != null && serialPort.isOpen();
    }

    boolean isPortOpen() {
        return serialPort.isOpen();
    }

    /**
     * RECONFIGURE 后设备重 load 时应用新 comm 设置（F-34，bug-record-20260826-001300）。
     * 分两档处理：
     * <ul>
     *   <li>物理参数（baudrate/dataBits/stopBits/parity/flowControl）变化：旧 fd 持旧参数
     *       无法热改，重建作为一条高优先级端口动作对话入队（排在在等/在飞对话之后，轮到
     *       它才在口执行线程重建——句柄开/关只在口执行线程发生），配置流线程有界同步等
     *       回执，超预算显式抛 {@link SerialReconfigureException}；共享此端口的全部
     *       source 引用不变，随新 fd 继续工作；</li>
     *   <li>timeout-only 变化：纯软件参数（{@link #getTimeout()} 读 serialInfo，不下发 OS），
     *       原位替换 SerialInfo 即生效，不重建端口（避免无谓 churn）。</li>
     * </ul>
     *
     * @param newInfo RECONFIGURE 提交的新 SerialInfo（portName 与本端口一致）
     * @param identity 触发方标识（日志定位用）
     */
    void applyReconfiguredSettings(SerialInfo newInfo, String identity) {
        if (serialInfo.settingsMatch(newInfo)) {
            if (serialInfo.timeout != newInfo.timeout) {
                log.info("[RECONFIGURE-TIMEOUT] port={}, identity={}, timeout {} -> {}（纯软件参数，不重建端口）",
                        serialInfo.portName, identity, serialInfo.timeout, newInfo.timeout);
                serialInfo = newInfo;
            }
            return;
        }
        log.warn("[RECONFIGURE-QUEUED] port={}, identity={}, 物理参数变化 [{}] -> [{}]，重建命令入队（排在在等/在飞对话之后）",
                serialInfo.portName, identity, serialInfo.settingsDescription(), newInfo.settingsDescription());
        SerialPortExecutor.Conversation conv;
        try {
            conv = executor.submitAction(null, reconfigureRebuildWaitMs, () -> {
                log.warn("[RECONFIGURE-REOPEN] port={}, identity={}, 重建命令获得执行权，close+reopen 重建端口",
                        serialInfo.portName, identity);
                stopPolling();
                closeFd("[reconfigure-rebuild]");
                serialInfo = newInfo;
                openPortNow(identity);
            });
        } catch (PortQueueFullException e) {
            throw new SerialReconfigureException("reconfigure 重建命令被拒（对话队列满）: port="
                    + serialInfo.portName + ", identity=" + identity + ", cause=" + e.getMessage());
        } catch (SerialSourceClosedException e) {
            throw new SerialReconfigureException("reconfigure 重建命令被拒（端口已退役）: port="
                    + serialInfo.portName + ", identity=" + identity + ", cause=" + e.getMessage());
        }
        try {
            conv.result.get(reconfigureRebuildWaitMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 预算耗尽：仍在队的重建命令自摘离队（无死命令残留，端口状态原样未动）
            executor.abandonQueued(conv, "reconfigure rebuild wait exhausted");
            throw new SerialReconfigureException("reconfigure 重建命令未在预算(" + reconfigureRebuildWaitMs
                    + "ms)内获得执行权（等待超时或对话队列满），port=" + serialInfo.portName
                    + ", identity=" + identity + "——前方对话长期在飞或已挂死，端口状态未动，可重试或走 disable/enable 兜底");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SerialReconfigureException("reconfigure 重建等待被中断: port=" + serialInfo.portName
                    + ", identity=" + identity);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new SerialReconfigureException("reconfigure 重建执行失败: port=" + serialInfo.portName
                    + ", identity=" + identity + ", cause=" + e.getCause().getMessage());
        }
    }

    /**
     * reconfigure 重建等待预算（毫秒）：重建动作入队后配置流线程 park 的最长时长。量级论证
     * =「最长一笔事务硬超时 + fd 重开耗时」：事务级硬超时由设备侧显式传入、量级秒级，队列
     * 前方至多若干笔各受自身硬超时约束的对话（容量 3）；fd 重开为百毫秒级。60s 覆盖最长
     * 合法对话完成 + 队列排空 + 重开；超预算仍等不到执行权 = 前方存在挂死写等病态形态，
     * 显式失败上抛交 core 分诊链处理，优于无限等待钉死配置流线程。
     */
    static final long RECONFIGURE_REBUILD_WAIT_MS = 60_000L;

    /** 重建等待预算（{@link #RECONFIGURE_REBUILD_WAIT_MS} 的实例态，红测注入口可缩短）。 */
    private long reconfigureRebuildWaitMs = RECONFIGURE_REBUILD_WAIT_MS;

    /** 红测注入口：缩短 reconfigure 重建等待预算（仅同包测试使用；生产用默认 60s）。 */
    void setReconfigureRebuildWaitMsForTest(long waitMs) {
        if (waitMs <= 0) {
            throw new IllegalArgumentException("reconfigureRebuildWaitMs must be > 0, got: " + waitMs);
        }
        this.reconfigureRebuildWaitMs = waitMs;
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
     * 本端口的传输资源键（serial-io:{portName}）：端口对象生命周期内不变。
     * 互斥对象 = 一条物理串口总线（同口所有对话串行，异口并行）。仅作
     * {@link SerialIoEvent} 的诊断词汇（requestId/日志定位）。
     */
    private static final String IO_RESOURCE_KEY_PREFIX = "serial-io:";

    String ioLaneKey() {
        return IO_RESOURCE_KEY_PREFIX + getPortName();
    }

    /**
     * 本端口 IO 执行域：域自持 {@link SerialIoPool} 的 per-port 串行视图（同口幂等）。
     * 设备可见 future 的完成回调/入站 finalize 等收尾链统一经此执行域（口执行线程永不跑
     * 设备代码，设备侧异步消费面在域池 worker 上）。
     */
    ExecutorService ioExecutor() {
        return SerialIoPool.executorFor(getPortName());
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

    // ========== Inbound frame event submission ==========

    /** 入站帧事件 requestId 序号（per-port，契约 §2.3 io-serial-{port}-{seq}）。 */
    private final AtomicLong inboundSeq = new AtomicLong();

    /**
     * IO 线程（sweeper/监听器回调）投递已定界的完整入站帧：构造 {@link SerialIoEvent}
     * 载荷 → 经 {@link SerialEventDispatcher} 并入域池 per-port 串行视图的同口 FIFO。
     * 调用线程=serial-io-sweeper（或监听器通知线程）；O(1) 入队即返，永不阻塞 IO 线程。
     *
     * @param frameBytes   已定界的完整响应帧（IO 线程组帧产物）
     * @param finalizeBody 事件执行体：域池端口视图上执行的业务 finalize（complete
     *                     responseFuture，processResponse 续链由此触发），闭包捕获处理上下文
     */
    void submitInboundFrame(byte[] frameBytes, Runnable finalizeBody) {
        String portName = getPortName();
        SerialIoEvent event = new SerialIoEvent(ioLaneKey(), portName, frameBytes,
                "io-serial-" + portName + "-" + inboundSeq.incrementAndGet());
        SerialEventDispatcher.submit(event, finalizeBody);
    }

    // ========== Buffer management（归口执行线程私有） ==========

    /** 清空接收缓冲区（口执行线程调用；提交面无旁路——缓冲无锁的前提是单写单清单读全在执行线程）。 */
    void clearReceiveBuffer() {
        continuousReceiveBuffer.clear();
    }

    /** 快照读并清空接收缓冲（口执行线程调用：排空丢弃、回放请求、缓冲直读共用）。 */
    byte[] readAndClearReceiveBuffer() {
        return continuousReceiveBuffer.readAndClear();
    }

    /** 追加到达字节（口执行线程调用）。 */
    void appendReceiveBuffer(byte[] data, int length) {
        continuousReceiveBuffer.append(data, 0, length);
    }

    // ========== 执行者访问原语（仅口执行线程调用） ==========

    /** 底层 jSerialComm 口对象（写路径用；可能为 null——未开过口）。 */
    SerialPort jSerialPort() {
        return serialPort;
    }

    /** RX 帧捕获（口执行线程在字节事件处理点调用；归属=当前在飞对话 owner）。 */
    void traceRx(byte[] data, int length, ResourceOwner owner) {
        CommTraceBuffer.instance().rx(CommTraceTransport.SERIAL, serialInfo.portName, data, length,
                null, null, owner);
    }

    /** TX 帧捕获（口执行线程在写成功点调用）。 */
    void traceTx(byte[] bytes, ResourceOwner owner) {
        CommTraceBuffer.instance().tx(CommTraceTransport.SERIAL, serialInfo.portName, bytes, null, owner);
    }

    /** TX 通道错误计数（写反压路径，不产生 TX 帧，如实）。 */
    void traceTxError() {
        CommTraceBuffer.instance().error(CommTraceTransport.SERIAL, serialInfo.portName);
    }

    /** 通知全部已连接 source 的监听器（口执行线程在字节事件处理点调用）。 */
    void notifySources(byte[] data, int length) {
        notifyDataReceived(data, length);
    }

    /** drain 内核 RX 的最大轮数（每轮读空当次 bytesAvailable；超轮仍有数据=异常形态，如实上报）。 */
    private static final int MAX_RX_DRAIN_ROUNDS = 64;

    /**
     * 重开后的 RX 清洗（F-43 清洗②）：drain 内核 tty RX 队列（jSerialComm closePort 不保证
     * TCFLSH，重开前在途的应答尾巴可残留内核层）+ 清应用层接收缓冲。每次成功打开后调用
     * ——覆盖看门狗强拆重开、reconfigure 物理参数重建、写反压自动重开三条路径，保证重开
     * 后首读零旧字节（旧字节会被误配对给新对话的首条命令）。
     *
     * @param reason 触发路径标识（日志定位用：port-open / drain-reopen 等）
     */
    void drainAndClearReceiveBuffer(String reason) {
        clearReceiveBuffer();
        int drained = 0;
        int rounds = 0;
        while (serialPort != null && serialPort.isOpen() && rounds++ < MAX_RX_DRAIN_ROUNDS) {
            int available = serialPort.bytesAvailable();
            if (available <= 0) {
                return;
            }
            byte[] sink = new byte[available];
            serialPort.readBytes(sink, available);
            drained += available;
        }
        if (drained > 0) {
            log.info("[RX-DRAIN] port={}, 触发={}, 丢弃重开残留旧字节 {} B", serialInfo.portName, reason, drained);
        }
        if (rounds >= MAX_RX_DRAIN_ROUNDS && serialPort != null && serialPort.bytesAvailable() > 0) {
            // 有界 drain 后内核仍有数据=对端持续回灌（如故障设备刷屏），如实上报不无限循环
            log.warn("[RX-DRAIN] port={}, 触发={}, {} 轮 drain 后内核仍有 {} B，疑似对端持续回灌",
                    serialInfo.portName, reason, MAX_RX_DRAIN_ROUNDS, serialPort.bytesAvailable());
        }
    }

    // ========== Port read polling（只在口执行线程开/停；pollLock 退役） ==========

    /**
     * 轮询周期（毫秒），对齐 IO 线程收敛调研建议（≤50ms，modbus4j InputStreamListener 同量级）。
     */
    static final long POLL_PERIOD_MS = 25L;

    /**
     * 启动端口读轮询（openPort 成功后调用；测试模式不调用，沿用旧 isTestMode 边界）。
     * 先取消既有任务再挂新任务——重开/并发注册场景下保证单口单任务，不会双任务同口双读
     * （开/停只发生在口执行线程，单点串行）。
     */
    void startPolling() {
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

    /**
     * 停止端口读轮询（退役/rebuild/恢复时调用；幂等）。
     * 口被写反压/jSerialComm 自动关闭的场景由 {@link SerialPortPollTask} 的 -1 哨兵自取消兜住，
     * 两条停止路径任一先到即停。
     */
    void stopPolling() {
        ScheduledFuture<?> handle = pollTask;
        pollTask = null;
        if (handle != null) {
            handle.cancel(false);
        }
    }

    /** 当前轮询任务句柄（生命周期断言用；无任务为 null）。 */
    ScheduledFuture<?> getPollTaskHandle() {
        return pollTask;
    }

    /** Modbus 直持 InputStream 期间轮询任务是否让路。 */
    boolean isPollPaused() {
        return pollPaused;
    }

    /**
     * 处理接收到的数据（轮询任务在读到字节后调用）：转发为执行者帧事件——排空窗丢弃、
     * 终态丢弃、CommTrace 捕获、缓冲追加与监听器通知都在口执行线程单点完成。
     *
     * @param data 接收到的数据
     * @param length 数据长度
     */
    void handleIncomingData(byte[] data, int length) {
        executor.submitBytes(data, length);
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

    /** 监听器注册时的缓冲回放（既有 addDataListener 语义）：经执行者事件到口执行线程快照投递。 */
    void deliverBufferedData(SerialDataListener listener) {
        executor.submitReplay(listener);
    }

    // ========== 恢复与强拆（执行者承载） ==========

    /**
     * 挂死端口自愈（Q-1/A2）：close + reopen 强拆挂死的本地阻塞 IO。
     *
     * <p>应用场景：jSerialComm {@code writeBytes} 是本地阻塞写（内核态持 fd），挂死时占用
     * 口执行线程且自身无法自救。恢复请求经执行者事件串行：空口立即 close+reopen；对话
     * 在飞时挂起至收场后执行（恢复不中断进行中对话）。硬超时看门狗的主路径不等本事件
     * ——看门狗线程直接跨线程 closePort（现有安全机制），口执行线程得救后经 RECOVERING
     * 重开。触发方 = 事务级硬超时（SerialTransactionStrategy/执行者看门狗），即「端口 IO
     * 挂死」的强证据时刻；亦供 modbus RTU 等直持串口流的集成在其事务硬超时路径上复用。
     *
     * @param reason 触发原因（日志定位用，如 transaction-hard-timeout）
     */
    void recoverWedgedPort(String reason) {
        executor.submitRecover(reason);
    }

    /**
     * 标记当前事务已被硬超时强拆：执行者强拆当前在飞对话（其 future 以超时异常完成）并
     * 进入排空——排空窗内被掐对话的残留补发（写与响应两侧）全部拒绝/丢弃，堵与新对话
     * 交错的复活路径（bugs/bug-record-20260826-093000 Q1 的事故面，由「严格串行 + 排空」
     * 承接）。由事务级硬超时路径调用。
     */
    void markTransactionAborted() {
        executor.submitAbort("mark-transaction-aborted");
    }

    // ========== Test environment detection ==========

    /**
     * 检测是否在测试环境
     *
     * <p>只认显式 test.mode 系统属性，不扫构造线程栈：ruoyi Spring MVC 以 Method.invoke
     * 反射分发 REST，栈恒含 sun.reflect 帧——栈扫描会把运行期创建的端口误判成测试环境
     * （2026-09-18 saimosen v2 运行期重加全离线事故同根因收口）。测试如何正确声明模式
     * （含消费仓 mock 源必须在 pom surefire 声明 test.mode 的原因）见本仓 README
     * 「测试编写规范（应答策略双读法与 test.mode 声明）」。
     *
     * @return true 如果在测试环境
     */
    private boolean detectTestEnvironment() {
        // 1. 检查系统属性（唯一读取点 SerialTestSignals；端口构造期无源对象可查，
        //    mock/代理类名等源对象级信号由应答策略对端口实例判定）
        if (SerialTestSignals.testModeDeclared() || SerialTestSignals.junitPropertyPresent()) {
            return true;
        }

        return false;
    }
}
