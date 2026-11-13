package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.MDC;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.CommTrace.ResourceOwner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SerialSourcePort manages the underlying serial port resource.
 * It is shared by multiple SerialSource instances that connect to the same port.
 * Package-private — not exposed to external callers.
 *
 * <p><b>锁入口使用规则（SDK 调度纪律）</b>：本类的 acquire/acquirePollingBounded 只应有
 * {@link SerialTransactionStrategy} 的两个事务入口（及 serial-tcp-server 网关这类自管
 * 收发时序的框架型消费方）调用，集成设备代码不应直接取锁。两个入口是两种调度纪律而非冗余：
 * <ul>
 *   <li>命令/写事务 → {@code executeWithLambda}：阻塞排队等锁（命令队列有限等待）——
 *       命令的时效语义是「最终要执行」，等是正确的；</li>
 *   <li>周期轮询 → {@code executePolling}：经 {@link #acquirePollingBounded(ResourceOwner, long)}
 *       取锁——无竞争快路径直达，锁忙时 FIFO 有界等待移交 IO 旁池线程（20260913-073600
 *       方案 c），预算耗尽才弃本轮（「过期即弃」保留在预算边界上）。此前「锁忙立即弃轮」
 *       的零等待形态在 fixedDelay 相位锁定下使共享口输家分钟级零帧（modbus C2 实证，
 *       serial 同构风险），已被本契约取代。</li>
 * </ul>
 * round/事务临界体内需要追加直发命令时，使用 SDK 注入的 source 直接 send（此时锁已持有，
 * 参照 gassensor PM3006SDevice 直发惯用法），勿再经事务入口二次取锁——同线程嵌套取锁是
 * 自死锁形态，由同线程嵌套守卫 fail-fast 立即抛（bug-record-20260829-082100 vaisala
 * 事故：旧形态静默 park 5h44m 无声失败）。
 *
 * <p><b>准入本体（C2 命令队列化，Actor 事务准入设计 §1/§5 阶段①）</b>：每口一条有界
 * 命令队列（{@link #commandQueue}，容量=构造参数）+ 单点串行授予路径取代旧
 * 「waitQueue + condition.signal 等待者竞速」形态：授予/清零的全部状态迁移
 * （{@link #grantUnderLock}/{@link #grantNextUnderLock}/{@link #completeUnderLock}）
 * 只在 {@code lock} 临界区内发生——release/幽灵锁收割点<b>主动移交队头</b>（确定性 FIFO，
 * 唤醒后竞速抢锁的 handed-off race 结构性消失），等待者 park 在自己的授予回执 future 上
 * （准入推进不占任何线程；轮询面的等待按既有契约移交 IO 旁池线程，定时线程零 park）。
 * 队满=显式拒绝（轮询面=弃本周期语义）；收尾纪律（Q-1/A2）：移交在释放线程上内联完成，
 * 不向任何执行者/车道线程排队。
 *
 * @author coffee
 */
public class SerialSourcePort {
    private static final Log log = LogFactory.getLogger(SerialSourcePort.class);
    private final Lock lock = new ReentrantLock();
    private final int maxWaiters;
    private String currentKey;
    private volatile long lockAcquireTime;
    private volatile String lockAcquireThread;
    /**
     * 持锁线程记账（36 号设计·同线程嵌套取锁守卫）：授予点记 {@code Thread} 引用——
     * 非线程名/线程 ID 字符串（线程 ID 复用会误判同线程）；release 与幽灵锁收割清 null。
     * 守卫判据 {@code Thread.currentThread() == lockHolderThread} 即同线程嵌套取锁。
     */
    private volatile Thread lockHolderThread;
    /**
     * 当前持锁事务的权威归属（io-resource-owner §4「当前住户」）：acquire/acquirePollingBounded
     * 授予点经视图自动注入登记（{@code SerialSource.lockOwner()}），release 与幽灵锁收割两路径清除。
     * TX（doSendWrite 持锁临界区内）与 RX（handleIncomingData 端口共享读线程，永无任务 MDC）
     * 两捕获点同读此字段作 CommTrace 权威参数——多设备同口的帧归属判官=事务锁。
     * null = 无 owner 注入（LEGACY 视图/无锁路径，归因回落 MDC/回填现状）。
     */
    private volatile ResourceOwner lockAcquireOwner;

    /**
     * 有界事务命令队列（准入本体，C2 命令队列化）：元素为 {@link AdmissionCommand}
     * （一笔「请求授予」的占位：key/owner/等待线程/授予回执 future）。入队=offer（队满
     * 显式拒绝），出队=授予点 poll（release/收割点主动移交队头）或超时方自摘 remove——
     * 生命周期全部经 JDK 队列原语管理，无平行状态。容量=构造参数 maxWaiters（生产默认
     * 见 {@code SerialIntegration.COMMAND_QUEUE_CAPACITY}，192 量级，设计 §1）。
     * 入队/出队/自摘均在 {@code lock} 临界区内，与 currentKey 状态迁移原子配对。
     */
    private final ArrayBlockingQueue<AdmissionCommand> commandQueue;

    /**
     * 准入命令（队列元素）：等待者把「我要一次授予」物化为命令对象入队，park 在
     * {@link #grantFuture} 上等回执——授予由释放/收割点在 lock 临界区内完成回执
     * （确定性 FIFO 移交，无唤醒竞速窗口）。{@code granted} 仅在 lock 临界区内读写，
     * 供超时/中断方区分「已被移交」（授予不得丢失）与「仍在队」（自摘返 null）。
     */
    private static final class AdmissionCommand {
        final String key;
        final ResourceOwner owner;
        final Thread waiter;
        final CompletableFuture<String> grantFuture = new CompletableFuture<>();
        boolean granted;

        AdmissionCommand(String key, ResourceOwner owner, Thread waiter) {
            this.key = key;
            this.owner = owner;
            this.waiter = waiter;
        }
    }

    /**
     * 幽灵锁收割阈值：currentKey 持续超过该时长即判定持锁事务已死（release 永久缺失），
     * 强制清锁救活端口。合法事务的事务级硬超时是秒级（SerialTransactionStrategy），
     * 5 分钟远超任何合法持锁时长，误收割风险可忽略。package-private 非 final 供红测缩短。
     */
    static final long DEFAULT_GHOST_REAP_THRESHOLD_MS = 300_000L;
    private long ghostReapThresholdMs = DEFAULT_GHOST_REAP_THRESHOLD_MS;

    /**
     * 红测注入口：缩短幽灵锁收割阈值（仅同包测试使用；生产用默认 5 分钟）。
     */
    void setGhostReapThresholdMsForTest(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("ghostReapThresholdMs must be > 0, got: " + thresholdMs);
        }
        this.ghostReapThresholdMs = thresholdMs;
    }

    SerialPort serialPort;
    volatile SerialInfo serialInfo;
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
     * 退役标记（8-2 退役门，bugs/bug-record-20260901-214000 / bug-record-20260901-103824 同根因）：
     * 最后一个 source 注销（空源分支）时置 true——本对象随即 closePort 并经
     * {@code integration.removePort} 从集成端口地图除名。此后本对象上的任何 openPort
     * （recoverWedgedPort 迟到自愈 / doSendWrite 写前自动重开 / applyReconfiguredSettings
     * 重建）都是迟到重开：物理串口本身随时可重开（新设备注册走地图里的<b>新</b>
     * SerialSourcePort 对象，正常打开），但已除名对象持有的任何 fd 都将成为无人关闭的
     * 孤儿——独占物理口使同口新设备 OPEN FAILED 直到 core 重启（lsof 实证）。
     * 与 {@link #lifecycleLock} 配对关闭 check-then-act 竞态：置位+拆除与 openPort 全程互斥。
     */
    private volatile boolean retired = false;

    /** 端口生命周期锁：unregisterSource 空源拆除（置退役门 + close + 除名）与 openPort 互斥。 */
    private final Object lifecycleLock = new Object();

    /**
     * Package-private constructor. Only SerialIntegration should create instances.
     */
    SerialSourcePort(SerialInfo serialInfo, int maxWaiters, SerialIntegration integration) {
        this.maxWaiters = maxWaiters;
        this.commandQueue = new ArrayBlockingQueue<>(maxWaiters);
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
                // Last source disconnected — close port and remove from integration map.
                // 整段拆除与 openPort 同锁互斥（先置退役门再拆除）：若迟到的 recoverWedgedPort/
                // 写前自动重开先于本分支拿到执行权，会把全新 fd 挂在已除名对象上成为孤儿
                // （bugs/bug-record-20260901-214000 / bug-record-20260901-103824 同根因）。
                synchronized (lifecycleLock) {
                    retired = true;
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
     * 在 lifecycleLock 内复查退役门后挂靠，杜绝把消费方源挂到已拆除对象上的半死挂靠。
     * retired = 设备已摘（本对象已除名），如实返回 null（未注册语义，不另开资源）。
     */
    SerialSource attachSourceForDevice(ResourceOwner owner) {
        synchronized (lifecycleLock) {
            if (retired) {
                return null;
            }
            // SerialSource 构造内的 registerSource→openPort 会再入 lifecycleLock（可重入，安全）
            return new SerialSource(this, owner);
        }
    }

    // ========== Lock management ==========

    /**
     * 残留写闸门（F-43 清洗①，bugs/bug-record-20260826-093000 Q1）：per-port 事务代数。
     * acquire/acquirePollingBounded 授予时递增 {@link #txGeneration} 并刷新 {@link #activeGeneration}；
     * 硬超时强拆时 {@link #markTransactionAborted()} 把 {@link #minLiveGeneration} 抬到
     * 当前代 + 1——被掐事务内尚未发出的 asyncSendData 提交（代数 < minLive）全部在写口拒绝，
     * 堵「强拆后 delay 定时器到点、剩余命令补发到（可能已重开的）端口与新事务交错」的复活路径
     * （F-42 受控实验：被掐轮 fpmset 在强拆 18s 后落 sim）。新事务 acquire 授予新代后闸门自动放行。
     */
    private final AtomicLong txGeneration = new AtomicLong();
    private volatile long activeGeneration;
    private volatile long minLiveGeneration;

    /** 硬超时强拆标记：被掐事务代内的后续发送一律拒绝（见 {@link #txGeneration} 注释）。 */
    void markTransactionAborted() {
        minLiveGeneration = txGeneration.get() + 1;
        log.warn("[TX-ABORTED] port={}, 代数 {} 内的残留写将被拒绝（下代 {} 起放行）",
                serialInfo.portName, txGeneration.get(), minLiveGeneration);
    }

    /** 授予新事务代数（acquire/acquirePollingBounded 持锁临界区内调用）。 */
    private void grantGeneration() {
        activeGeneration = txGeneration.incrementAndGet();
    }

    /**
     * 残留写检查（写口统一防线）：发送提交时捕获的代数 < 当前最低存活代数 = 被掐事务的
     * 补发命令，拒绝落端口。
     */
    private void assertWriteLive(long txTag) {
        if (txTag < minLiveGeneration) {
            throw new SerialWriteException("残留写拒绝: 事务已被硬超时强拆 (txTag=" + txTag
                    + " < minLive=" + minLiveGeneration + "), port=" + serialInfo.portName);
        }
    }

    /**
     * 写路径等锁的默认 park 预算（秒）：命令/写事务「最终要执行」的有限等待语义
     * （{@link #acquire()} 无参入口取本值）。常量提取供契约测试零耗时断言——
     * 轮询非阻塞化后写路径注入点增多，默认值漂移须可被立即发现。
     */
    static final long DEFAULT_ACQUIRE_WAIT_SECONDS = 5;

    /**
     * 尝试获取锁，支持等待队列
     * @return 锁标识（成功获取或进入等待），null表示无法获取且超出等待队列容量
     */
    String acquire() {
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS, null);
    }

    /**
     * 尝试获取锁，支持等待队列和超时
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取或命令队列队头移交），null表示超时或命令队列满
     */
    String acquire(long timeout, TimeUnit unit) {
        return acquire(timeout, unit, null);
    }

    /** 带权威归属的阻塞取锁（视图入口形态）：授予点登记 {@link #lockAcquireOwner}。 */
    String acquire(ResourceOwner owner) {
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS, owner);
    }

    /**
     * 尝试获取锁，支持等待队列和超时，授予点登记持锁权威归属（io-resource-owner §4）。
     * 两个授予点（快速路径 + 释放/收割点队头移交）都登记；owner 为 null = 无注入
     * （LEGACY 视图），归因回落线程 MDC/回填现状。
     *
     * <p>C2 命令队列化：锁忙时入队 {@link AdmissionCommand} 并 park 在授予回执 future 上，
     * 预算内被释放/收割点确定性移交（FIFO 队头，无唤醒竞速）。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 锁标识（成功获取或队头移交），null表示超时或命令队列满（队满=显式拒绝）
     */
    String acquire(long timeout, TimeUnit unit, ResourceOwner owner) {
        String requestKey = generateRequestKey();
        AdmissionCommand cmd = new AdmissionCommand(requestKey, owner, Thread.currentThread());
        lock.lock();
        try {
            reapGhostLockIfStale("acquire");
            assertNoSameThreadNestedAcquire("acquire");
            if (currentKey == null) {
                grantUnderLock(requestKey, owner, Thread.currentThread());
                return requestKey;
            }
            if (!commandQueue.offer(cmd)) {
                log.warn("Command queue full (capacity " + maxWaiters + "), request rejected: " + requestKey);
                return null;
            }
        } finally {
            lock.unlock();
        }
        // 等待授予回执：park 在自己的 future 上（准入推进不占线程），移交在释放/收割线程
        // 上内联完成（Q-1/A2 收尾纪律：不向执行者/车道线程排队）
        try {
            return cmd.grantFuture.get(timeout, unit);
        } catch (java.util.concurrent.TimeoutException e) {
            return abandonUnlessGranted(cmd, "Acquire timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abandonUnlessGranted(cmd, "Wait interrupted: " + requestKey);
        } catch (java.util.concurrent.ExecutionException e) {
            // 结构不可达：grantFuture 只会被 complete(key)，无异常完成路径——到达即状态机
            // 缺陷，如实上抛不吞
            throw new IllegalStateException("Grant future failed unexpectedly: " + requestKey, e.getCause());
        }
    }

    /**
     * 超时/中断方的离队或认领（lock 临界区内）：已被释放/收割点移交（granted）则授予
     * 不得丢失——返回 key 交由调用方照常 release（与移交的竞速输家，付一次超额等待的
     * 代价优于丢授予成幽灵锁）；仍在队则自摘占位返回 null（不泄漏、不挡后续队头）。
     */
    private String abandonUnlessGranted(AdmissionCommand cmd, String reason) {
        lock.lock();
        try {
            if (cmd.granted) {
                return cmd.key;
            }
            commandQueue.remove(cmd);
            log.warn(reason + ": {}, lock currently held by: {} (acquired at {} by thread {}), queue depth: {}",
                    cmd.key, currentKey, lockAcquireTime, lockAcquireThread, commandQueue.size());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 授予状态迁移（{@code lock} 临界区内调用）：currentKey 的唯一写入口之一，两个授予点
     * （快速路径 {@link #acquire}/{@link #acquireUncontended} 与释放/收割点队头移交
     * {@link #grantNextUnderLock}）共用——授予与代数刷新（grantGeneration）同一临界块，
     * 无第二套授予路径。
     */
    private void grantUnderLock(String key, ResourceOwner owner, Thread holderThread) {
        currentKey = key;
        grantGeneration();
        lockAcquireTime = System.currentTimeMillis();
        lockAcquireThread = holderThread.getName();
        lockHolderThread = holderThread;
        lockAcquireOwner = owner;
    }

    /**
     * 释放/收割点主动移交队头（{@code lock} 临界区内调用，设计 §2「release 点主动移交
     * 队头」）：状态清零与队头授予同一不可分割动作——不存在「currentKey==null 且队列
     * 非空」的可观测中间态，快速路径无需复查队列，等待者无唤醒后竞速窗口（旧
     * handed-off race 形态的结构性根除）。队头已在超时竞速中离队由 poll 自然跳过。
     * 在执行清零的线程上内联完成（不向执行者/车道线程排队，Q-1/A2）。
     */
    private void grantNextUnderLock() {
        AdmissionCommand head = commandQueue.poll();
        if (head == null) {
            return;
        }
        head.granted = true;
        grantUnderLock(head.key, head.owner, head.waiter);
        // 回执在临界区内完成：等待者醒来时授予状态已完全就绪；future 无依赖链
        // （仅 get 等待方），complete 不会在临界区内级联执行任意用户代码
        head.grantFuture.complete(head.key);
    }

    /** 轮询真弃轮计数（E2/R3 记账：有界等待预算耗尽才计，禁静默）。 */
    private final AtomicLong lockBusySkipCount = new AtomicLong();
    /** 锁忙放弃日志限频时间戳（锁临界区内读写，ReentrantLock 保证可见性，仅日志用）。 */
    private volatile long lastBusySkipLogAt;
    /** 锁忙放弃日志限频间隔：默认 60s 一条（饱和期 ~30 次/min 的放弃若不限频会刷爆日志）。 */
    static final long BUSY_SKIP_LOG_INTERVAL_MS = 60_000L;

    /** 获取累计真弃轮次数（轮询有界等待预算耗尽的计数，运行时可观测用）。 */
    public long getLockBusySkipCount() {
        return lockBusySkipCount.get();
    }

    // ========== C1 只读观测缝（test-migration-spec-e1-e4 §5；C2 已切换为命令队列本体真源） ==========

    /**
     * 命令队列深度观测缝①（只读）：已提交、尚未开始执行的排队命令数，<b>不含在飞命令</b>
     * （C2 兑现 C1 javadoc 承诺：真源 = 命令队列 {@link #commandQueue} 本体 size，锁内
     * 一致快照，无平行记账——「队列内残留已死命令」可被深度断言抓住）。在飞与否由
     * {@link #hasInFlight()} 观测——两者<b>成对</b>使用才等价覆盖旧等待者计数语义
     * （单独读深度存在「深度==0 但有命令在途」盲区）。
     */
    int getQueueDepth() {
        lock.lock();
        try {
            return commandQueue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在飞命令观测缝②（只读）：执行者是否正执行一笔命令 = 端口锁已被授予且未释放
     * （{@code currentKey != null}，现有状态单一真源）。任意采样时刻至多对应一个在飞
     * 命令（「至多一个授予者」不变量的布尔面；越序/窃取中间态的精确判据见
     * {@link #getGrantedCount()}/{@link #getCompletedCount()} 计数对）。在飞命令的
     * 归属标识经既有 {@link #getLockAcquireOwner()} 观测，不另设缝。
     */
    boolean hasInFlight() {
        lock.lock();
        try {
            return currentKey != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 授予计数观测缝③左半（只读）：已发生的授予数。真源 = 既有事务代数
     * {@code txGeneration}（快速路径与释放/收割点队头移交两个授予点均经
     * grantGeneration() 递增代数），零新增记账。与 {@link #getCompletedCount()}
     * 构成结果级不变量判据：{@code granted - completed ∈ {0,1}}（任意时刻至多一个
     * 授予未完成）。
     */
    long getGrantedCount() {
        return txGeneration.get();
    }

    /**
     * 完成计数观测缝③右半（只读）：已结束的授予数。清零点恰两处——正常 release 与
     * 幽灵锁收割（与 currentKey 状态清零同临界块递增，单一同步块精确记账，无第二套
     * 清零路径）；非法 release（key 不匹配）不计数。注意计数对为两次独立读，跨线程
     * 采样存在撕裂窗口（读 granted 前后恰有完成发生），不变量校验须按
     * 「completed 先读-后读夹逼 granted」范式容忍撕裂（见 BusArbitrationAtMostOneGranteeTest）。
     */
    long getCompletedCount() {
        return completedTxCount.get();
    }

    /** 完成计数（观测缝③；见 {@link #getCompletedCount()}）：与 currentKey 清零同临界块递增。 */
    private final AtomicLong completedTxCount = new AtomicLong();

    /**
     * 轮询锁获取契约（20260913-073600 修复，方案 c：轮询并入 FIFO 有界等待——与 modbus
     * {@code ModbusSource.acquirePollingBounded} 同型）：无竞争快路径直达；锁忙时把 FIFO
     * 有界等待移交 IO 旁池线程执行——调用线程（SDK 定时线程）零 park，轮询重新与写命令
     * 同队同公平（此前「锁忙立即弃轮」的零等待形态在 fixedDelay 相位锁定下使共享口输家
     * 分钟级零帧，是共享口饥饿的根因）。
     *
     * <p>返回 CF 语义：完成值非 null=锁标识（等待授予发生在旁池线程，事务体随 CF 依赖链
     * 在同一旁池线程执行，MDC 已恢复到提交时快照）；null=本周期真弃轮（预算耗尽/等待
     * 队列满/旁池饱和拒绝），源侧记账。等到了锁的轮次不计入
     * {@link #getLockBusySkipCount()}（观测不说谎）。
     *
     * <p><b>同线程嵌套守卫的行为差异（如实声明，非缺陷）</b>：快路径在调用线程上执行，
     * 守卫照常 fail-fast（round 临界体内经 executePolling 二次取锁立即抛）；锁忙分支的
     * acquire 在旁池线程上执行，与任何持锁者天然异线程，守卫按线程判定自然不触发——
     * 该形态的嵌套取锁退化为「队列内等外层释放」，由 acquire 超时/事务硬超时兜底，
     * 不另设守卫补强。
     *
     * @param owner    取锁权威归属（视图入口注入；LEGACY 传 null）
     * @param budgetMs 锁等待预算（毫秒，须 &gt; 0；SDK 侧按轮询周期 clamp）
     * @return 锁标识 CF；null 完成=本周期弃轮
     */
    CompletableFuture<String> acquirePollingBounded(ResourceOwner owner, long budgetMs) {
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("acquirePollingBounded 要求 budgetMs > 0: " + budgetMs);
        }
        // 快路径：无竞争轮次零等待、零记账（与旧轮询契约的非阻塞授予段行为一致）
        String fast = acquireUncontended(owner);
        if (fast != null) {
            return CompletableFuture.completedFuture(fast);
        }
        // 锁忙：等待移交 IO 旁池线程（定时线程零 park），预算内经 acquire 排队等队头交接。
        // 走域池直接提交面而非 per-port 串行车道——车道 FIFO 单飞会被 park 的等待钉死同口
        // 全部 IO，且后来的等待者无机会入锁等待队列（FIFO 公平被车道串行化破坏）。
        // MDC 提交时捕获、旁池任务内恢复——完成点之后的依赖链（事务体→帧捕获）在旁池
        // 线程上携带设备归属上下文，与快路径的定时线程形态归因一致。
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        CompletableFuture<String> out = new CompletableFuture<>();
        try {
            SerialIoPool.domainExecutor().execute(() -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    String key = acquire(budgetMs, TimeUnit.MILLISECONDS, owner);
                    if (key == null) {
                        // 真弃轮才记账：预算耗尽/队列满（等到锁的轮次不计，观测不说谎）
                        recordPollingSkip(budgetMs);
                    }
                    out.complete(key);
                } finally {
                    if (mdcContext != null) {
                        if (previousMdc != null) {
                            MDC.setContextMap(previousMdc);
                        } else {
                            MDC.clear();
                        }
                    }
                }
            });
        } catch (RejectedExecutionException poolSaturated) {
            // 旁池饱和（线程+有界队全满）或已停机：本周期弃轮，下一周期再试（源可自愈）
            recordPollingSkip(budgetMs);
            return CompletableFuture.completedFuture(null);
        }
        return out;
    }

    /**
     * 无竞争快路径取锁（旧轮询契约的授予段）：锁忙返回 null，等待/弃轮由调用方
     * 决定——是否真弃轮取决于等待结果，记账不在此处。
     */
    private String acquireUncontended(ResourceOwner owner) {
        String requestKey = generateRequestKey();
        lock.lock();
        try {
            reapGhostLockIfStale("acquire");
            assertNoSameThreadNestedAcquire("acquirePollingBounded");
            if (currentKey == null) {
                grantUnderLock(requestKey, owner, Thread.currentThread());
                return requestKey;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 真弃轮记账（E2/R3 可观测性：放弃必须可计数，禁静默）：计数 + 限频 warn。
     * 持锁方快照在锁内取，避免读到撕裂的 currentKey/时间戳组合。
     */
    private void recordPollingSkip(long budgetMs) {
        long skips = lockBusySkipCount.incrementAndGet();
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now - lastBusySkipLogAt >= BUSY_SKIP_LOG_INTERVAL_MS) {
                lastBusySkipLogAt = now;
                log.warn("Polling bounded-acquire budget exhausted ({}ms): port={}, total skips={}, "
                        + "lock currently held by: {} (acquired at {} by thread {})",
                        budgetMs, getPortName(), skips, currentKey, lockAcquireTime, lockAcquireThread);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放锁：状态清零 + <b>主动移交队头</b>（同一临界块内的不可分割动作，设计 §2）
     *
     * @param releaseKey 要释放的锁标识
     * @return 释放是否成功
     */
    boolean release(String releaseKey) {
        lock.lock();
        try {
            if (currentKey != null && currentKey.equals(releaseKey)) {
                completeUnderLock();
                grantNextUnderLock();
                return true;
            }
            log.warn("Invalid release key: " + releaseKey);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 授予完成的状态清零（{@code lock} 临界区内调用）：release 与幽灵锁收割共用的唯一
     * 清零路径（同一状态机，无双路径漂移），与 {@link #completedTxCount} 递增同临界块。
     */
    private void completeUnderLock() {
        currentKey = null;
        lockAcquireTime = 0;
        lockAcquireThread = null;
        lockHolderThread = null;
        lockAcquireOwner = null;
        completedTxCount.incrementAndGet();
    }

    /** 当前持锁事务的权威归属（捕获点读；无锁/无注入为 null）。 */
    ResourceOwner getLockAcquireOwner() {
        return lockAcquireOwner;
    }

    /**
     * 幽灵锁收割（Q-1/Q-2 二轮根因修复）：持锁事务的事务级硬超时保证 release 必执行，
     * 但 release 的执行链本身可能整体丢失（超时任务入队被拒后 {@code withHardTimeout} 的计时
     * future 永不完成、持有线程被上游设备同步等待永久吸收等）——此时 currentKey 成为
     * 永久幽灵锁：后续所有 acquire 只能超时返回，端口永久瘫痪（live 实证幽灵锁持锁 45min+，
     * WEDGE-RECOVERY 反复触发不收敛）。本方法在 acquire 入口与 recoverWedgedPort 双点检查：
     * 持锁时长超过 {@link #ghostReapThresholdMs} 即按 release 同一状态机强制清零
     * （currentKey/lockAcquireTime/lockAcquireThread + 主动移交队头），杜绝第二套清锁路径漂移。
     *
     * <p>必须在已持有 {@code lock} 的临界区内调用。收割不清空 commandQueue：队头命令由
     * 移交直接授予（超时竞速输家按 granted 认领，仍在队者自行摘除——既有语义不变）。
     *
     * @param trigger 触发点标识（日志定位用：acquire / wedge-recovery）
     */
    private void reapGhostLockIfStale(String trigger) {
        if (currentKey == null || lockAcquireTime <= 0) {
            return;
        }
        long heldMs = System.currentTimeMillis() - lockAcquireTime;
        if (heldMs <= ghostReapThresholdMs) {
            return;
        }
        log.error("[GHOST-LOCK-REAPED] port={}, trigger={}, 幽灵锁持锁 {}ms 超阈值 {}ms（持锁 key={}, 持锁线程={}），"
                        + "按 release 同一状态机强制清零，队头命令 {} 个在队待移交",
                serialInfo.portName, trigger, heldMs, ghostReapThresholdMs,
                currentKey, lockAcquireThread, commandQueue.size());
        // 与 release() 完全一致的状态清零 + 队头移交（同一状态机，无双路径漂移）
        completeUnderLock();
        grantNextUnderLock();
    }

    /**
     * 同线程嵌套取锁 fail-fast 守卫（36 号设计·方案 D 形态 A）：vaisala 事故
     * （bug-record-20260829-082100）的 SDK 层加固。事务体经 SerialTransactionStrategy
     * 的 executeHeld 在发起线程上同步执行（轮询快路径在定时线程、写路径在业务线程）——
     * round/事务临界体内再经 executeWithLambda/executePolling 二次取锁时，等待者与
     * 持有者是同一线程（等待 key 与持有 key 同为毫秒-线程ID），授予回执 future 永远等
     * 不到自己的 release：旧形态阻塞到超时返 null（live 实证同一线程静默空转 5h44m，
     * Acquire timeout 日志一直在却无人醒），守卫改为微秒级立即抛，错误直达根因。
     * 审计结论（36 号 §二）：20 仓全扫不存在合法的同线程嵌套取锁，无人依赖可重入。
     * 20260913 方案 c 后：轮询等待授予路径的事务体在 IO 旁池线程执行（见
     * {@link #acquirePollingBounded(ResourceOwner, long)} 的行为差异声明），该形态的
     * 嵌套取锁按线程判定自然不触发守卫，由 acquire 超时/事务硬超时兜底。
     *
     * <p>位置约束：必须在 {@link #reapGhostLockIfStale} 之后——同线程的陈年幽灵锁应
     * 先收割后守卫，否则跨阈值的二次取锁会抛而非收割（破坏 acquireReapsGhostLock 契约）。
     *
     * <p>命中时锁状态原样不动：等锁者失败不影响既有持有关系（持锁者仍可正常 release）。
     *
     * @param entry 入口标识（acquire / acquirePollingBounded 快路径，诊断定位用）
     */
    private void assertNoSameThreadNestedAcquire(String entry) {
        Thread holder = lockHolderThread;
        if (holder == null || holder != Thread.currentThread()) {
            return;
        }
        throw new IllegalStateException("同线程嵌套取锁（自死锁形态，fail-fast）: port=" + getPortName()
                + ", entry=" + entry
                + ", 持有者 key=" + currentKey
                + ", 持锁线程=" + holder.getName()
                + ", 已持锁 " + (System.currentTimeMillis() - lockAcquireTime) + "ms"
                + "；round/事务临界体内应使用注入 source 直发，勿再经 executeWithLambda/executePolling 二次取锁"
                + "（参照 gassensor PM3006SDevice 直发惯用法）；锁状态未变，既有持有关系不受影响");
    }

    private String generateRequestKey() {
        // 生成唯一请求标识（示例：时间戳+线程ID）
        return System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }

    // ========== Port management ==========

    private void openPort(String identity) {
        // 整个开端口流程在 lifecycleLock 下与 unregisterSource 空源拆除互斥：拆除侧先置
        // retired 再 close/除名，此处入口见 retired 即拒——两个互斥分支关闭「拆除中/拆除后
        // 仍把新 fd 挂上已除名对象」的 check-then-act 竞态（bug-record 214000/103824）。
        synchronized (lifecycleLock) {
            if (retired) {
                log.warn("[OPEN-REJECTED] port={}, identity={}, 已退役（最后一个 source 已注销），拒绝重开——"
                                + "本对象已从集成端口地图除名，任何 fd 都将成为孤儿（bug-record 214000/103824）",
                        serialInfo.portName, identity);
                return;
            }
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
    }

    boolean isPortOpen() {
        return serialPort.isOpen();
    }

    /**
     * RECONFIGURE 后设备重 load 时应用新 comm 设置（F-34，bug-record-20260826-001300）。
     * 此前 register() 同口复用从不对比新旧 SerialInfo：timeout 被静默吞掉、物理参数变化
     * 抛异常，均需 disable/enable 兜底重建才生效。分两档处理：
     * <ul>
     *   <li>物理参数（baudrate/dataBits/stopBits/parity/flowControl）变化：旧 fd 持旧参数
     *       无法热改，走 disable/enable 同款重建路径 stopPolling → closePort → openPort
     *       （{@link #openPort} 内按新 serialInfo 全量重设参数并重启轮询）。阶段②起重建
     *       <b>作为一条命令排入本口命令队列</b>（排在在等/在飞事务之后，轮到它才重建，
     *       设计 §3/S4），配置流线程有界同步等回执，超预算显式抛
     *       {@link SerialReconfigureException}；
     *       共享此端口的全部 source 引用不变，随新 fd 继续工作；</li>
     *   <li>timeout-only 变化：纯软件参数（{@link #getTimeout()} 读 serialInfo，不下发 OS），
     *       原位替换 SerialInfo 即生效，不重建端口（避免无谓 churn）。</li>
     * </ul>
     *
     * @param newInfo RECONFIGURE 提交的新 SerialInfo（portName 与本端口一致）
     * @param identity 触发方标识（日志定位用）
     */
    /**
     * reconfigure 重建命令的有界同步等待预算（毫秒，阶段② reconfigure 入队，设计 §3 选项 a
     * 「等一等」回执）：重建命令排入本口命令队列后，配置流线程 park 在授予回执 future 上
     * 等待的最长时长。量级论证=「最长一笔事务硬超时 + fd 重开耗时」：事务级硬超时由设备侧
     * 显式传入、量级秒级（多步标定流程取高值），队列前方至多若干笔各受自身硬超时约束的
     * 事务（正常轮转远低于预算）；fd 重开为百毫秒级。60s 覆盖最长合法事务完成 + 队列排空
     * + 重开；超预算仍等不到执行权 = 前方存在挂死写/幽灵锁等病态形态，显式失败上抛
     * （{@link SerialReconfigureException}）交 core 分诊链处理，优于无限等待钉死配置流线程。
     */
    static final long RECONFIGURE_REBUILD_WAIT_MS = 60_000L;

    /** 重建命令等待预算（{@link #RECONFIGURE_REBUILD_WAIT_MS} 的实例态，红测注入口可缩短）。 */
    private long reconfigureRebuildWaitMs = RECONFIGURE_REBUILD_WAIT_MS;

    /**
     * 红测注入口：缩短 reconfigure 重建命令等待预算（仅同包测试使用；生产用默认 60s）。
     */
    void setReconfigureRebuildWaitMsForTest(long waitMs) {
        if (waitMs <= 0) {
            throw new IllegalArgumentException("reconfigureRebuildWaitMs must be > 0, got: " + waitMs);
        }
        this.reconfigureRebuildWaitMs = waitMs;
    }

    void applyReconfiguredSettings(SerialInfo newInfo, String identity) {
        if (serialInfo.settingsMatch(newInfo)) {
            if (serialInfo.timeout != newInfo.timeout) {
                log.info("[RECONFIGURE-TIMEOUT] port={}, identity={}, timeout {} -> {}（纯软件参数，不重建端口）",
                        serialInfo.portName, identity, serialInfo.timeout, newInfo.timeout);
                serialInfo = newInfo;
            }
            return;
        }
        log.warn("[RECONFIGURE-QUEUED] port={}, identity={}, 物理参数变化 [{}] -> [{}]，重建命令入队（排在在等/在飞事务之后）",
                serialInfo.portName, identity, serialInfo.settingsDescription(), newInfo.settingsDescription());
        // 阶段② reconfigure 入队（设计 §3/S4）：重建命令经 acquire 占位于本口命令队列——
        // FIFO 排在全部在等/在飞事务之后，轮到它（release/收割点移交执行权）才 close+reopen，
        // 一次性消除「reconfigure 线程无锁协调地 close 物理口与在飞事务交错」的历史竞态。
        // 有界同步等待（选项 a）：慢活（close+openPort）在本调用方线程执行——命令队列只做
        // 顺序仲裁，不承载慢操作（设计 §6.1）；完成后 inline release 移交队头（Q-1/A2 收尾
        // 纪律：不排队到执行者自身线程）。
        String rebuildKey = acquire(reconfigureRebuildWaitMs, TimeUnit.MILLISECONDS, null);
        if (rebuildKey == null) {
            throw new SerialReconfigureException("reconfigure 重建命令未在预算(" + reconfigureRebuildWaitMs
                    + "ms)内获得执行权（等待超时或命令队列满），port=" + serialInfo.portName
                    + ", identity=" + identity + "——前方事务长期在飞或已挂死，端口状态未动，可重试或走 disable/enable 兜底");
        }
        try {
            log.warn("[RECONFIGURE-REOPEN] port={}, identity={}, 重建命令获得执行权，close+reopen 重建端口",
                    serialInfo.portName, identity);
            stopPolling();
            if (serialPort != null && serialPort.isOpen()) {
                serialPort.closePort();
            }
            serialInfo = newInfo;
            openPort(identity);
        } finally {
            release(rebuildKey);
        }
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
     * 互斥对象 = 一条物理串口总线（同口所有 send/read/收尾串行，异口并行）。
     * W2-1 后仅作 {@link SerialIoEvent} 的诊断词汇（requestId/日志定位）——互斥本体
     * 已由域池 per-port 视图承担（见 {@link #ioExecutor()}），不再进任何执行 API。
     */
    private static final String IO_RESOURCE_KEY_PREFIX = "serial-io:";

    String ioLaneKey() {
        return IO_RESOURCE_KEY_PREFIX + getPortName();
    }

    /**
     * 本端口 IO 执行域：域自持 {@link SerialIoPool} 的 per-port 串行视图（同口幂等）。
     * 同口 FIFO 串行——入站 finalize × 发帧读 × 写三类流量同口有序（RTU 总线本性，
     * 165500 承重顺序），异口并行。29 号 v2 S1 起替代引擎车道视图（executorFor(
     * "serial-io:port")——E1 的根治曾借引擎 per-port 车道，终态收编为域池+视图，
     * 引擎依赖归零）。
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

    // ========== Inbound frame event submission（P1：IO 线程只读字节+组帧+投递） ==========

    /** 入站帧事件 requestId 序号（per-port，契约 §2.3 io-serial-{port}-{seq}）。 */
    private final AtomicLong inboundSeq = new AtomicLong();

    /**
     * IO 线程（sweeper/监听器回调）投递已定界的完整入站帧（03 号设计 §4.4，W2-1 形态）：
     * 构造 {@link SerialIoEvent} 载荷 → 经 {@link SerialEventDispatcher} 并入域池
     * per-port 串行视图的同口 FIFO（与发帧读/写同队有序，165500 承重顺序）。
     *
     * <p>调用线程=serial-io-sweeper（或监听器通知线程）；O(1) 入队即返，永不阻塞 IO 线程。
     * 域池拒绝（饱和/停机）由 dispatcher 记账+告警，Transport 不重试（响应超时机制天然兜底）。
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

    /** drain 内核 RX 的最大轮数（每轮读空当次 bytesAvailable；超轮仍有数据=异常形态，如实上报）。 */
    private static final int MAX_RX_DRAIN_ROUNDS = 64;

    /**
     * 重开后的 RX 清洗（F-43 清洗②，bugs/bug-record-20260826-093000 Q2）：drain 内核 tty RX
     * 队列（jSerialComm closePort 不保证 TCFLSH，重开前在途的应答尾巴可残留内核层）+ 清应用层
     * continuousReceiveBuffer。{@link #openPort} 每次成功打开后调用——覆盖 recoverWedgedPort
     * 强拆重开、applyReconfiguredSettings 物理参数重建、写反压自动重开三条路径，保证重开后
     * 首读零旧字节（旧字节会被误配对给新事务的首条命令）。
     *
     * @param reason 触发路径标识（日志定位用：port-open / wedge-recovery 等）
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
        }, ioExecutor());
    }

    private CompletableFuture<byte[]> readFromBufferBytes(SerialSource requester) {
        return CompletableFuture.supplyAsync(() -> {
            assertRequesterActive(requester, "asyncReadDataBytes");
            bufferLock.lock();
            try {
                return continuousReceiveBuffer.readAndClear();
            } finally {
                bufferLock.unlock();
            }
        }, ioExecutor());
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
        // 通讯帧捕获：读路径唯一入口（轮询与事件路径共用），独立数据面零 logback 开销。
        // 权威归属=当前持锁 owner（io-resource-owner §5.2 捕获点）：本方法在端口共享读线程
        // 执行，永无任务 MDC——「同口至多一笔在飞」的锁纪律使 RX=持锁者应答结构性成立；
        // 锁已清的迟到/unsolicited 字节 owner 为 null，落既有 30s 回填→null 如实。
        CommTraceBuffer.instance().rx(CommTraceTransport.SERIAL, serialInfo.portName, data, length,
                null, null, lockAcquireOwner);
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

    /** 上次写发送时刻（回放时间窗判据：窗口=读超时×2，见 deliverBufferedData）。 */
    private volatile long lastSendTimeMs;

    /** 红测注入口：设定上次发送时刻（仅同包测试使用，构造「迟到旧字节」场景）。 */
    void setLastSendTimeForTest(long timeMs) {
        this.lastSendTimeMs = timeMs;
    }

    /** 回放时间窗（毫秒）：正常应答应在一次读超时内到达，×2 留余量；超过即视为迟到旧字节。 */
    private long replayWindowMs() {
        int timeout = serialInfo != null && serialInfo.timeout > 0 ? serialInfo.timeout : Const.READ_TIMEOUT_MS;
        return 2L * timeout;
    }

    void deliverBufferedData(SerialDataListener listener) {
        if (continuousReceiveBuffer.size() > 0) {
            // 回放时间窗（F-43 清洗②）：距上次发送超过窗的缓冲字节 = 强拆前在途/迟到多行应答的
            // 旧字节，回放会把旧帧整体误配对给新命令的监听器（F-42 Q2 形态），丢弃并如实记日志
            if (lastSendTimeMs > 0
                    && System.currentTimeMillis() - lastSendTimeMs > replayWindowMs()) {
                int staleBytes = continuousReceiveBuffer.size();
                clearReceiveBuffer();
                log.warn("[REPLAY-DROP] port={}, 缓冲 {} B 距上次发送超过回放窗 {} ms，按迟到旧字节丢弃",
                        serialInfo.portName, staleBytes, replayWindowMs());
                return;
            }
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

    /**
     * 异步写发送（29 号 v2 S1 起：恒经域池 per-port 串行视图，无「当前线程直发」旁路）。
     *
     * <p><b>[WRITE-INLINE] 逃逸口退役（071900 结构性根除）</b>：bugs/fixed/
     * bug-record-20260826-071900 的自锁形态是「core 写闸 ioBody 与内层写共享同一条
     * 引擎车道队列（serial-io:{port} 单线程车道）——任务体内的 join 等待排在自身之后
     * 的入队写，队头自锁」；当时的修复是检测「当前线程即本口车道 worker
     * （SchedulerEngine.currentExecutingLaneKey）」则当前线程直发不入队的逃逸口。
     * 域自持 SerialIoPool 后写闸任务体（引擎车道/业务线程）与写 IO（域池 per-port 视图）
     * 分属两个队列，跨执行域 join 天然完成——需要给自己开逃逸口的机制已不存在，逃逸口
     * 及其引擎检测逻辑一并删除（serial 自锁族的结构性根除实证，29 号 v2 S1）。
     */
    CompletableFuture<Boolean> asyncSendData(byte[] bytes) {
        // 残留写闸门：发送提交时捕获事务代数，写口按「代数是否仍存活」拒绝被掐事务的补发
        final long txTag = activeGeneration;
        return CompletableFuture.runAsync(() -> {
            doSendWrite(bytes, txTag);
        }, ioExecutor()).thenApply(v -> {
            return true;
        }).exceptionally(ex -> {
            // 【改动②-2 类型透传】SerialWriteException 原样向上抛（保留写失败语义供调用方识别），
            // 其余异常仍包成通用 RuntimeException（保持旧行为兼容）。
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw writeFailure(cause);
        });
    }

    /**
     * requester-aware 发送入口：owner view 关闭后，即使任务已入队也未开始执行，
     * IO task 体在真正触碰端口前拒绝。硬件写一旦开始则无法撤回——后续 close 只能
     * 拒绝更晚的新 IO，这是共享物理资源的不可回退边界。
     */
    CompletableFuture<Boolean> asyncSendData(SerialSource requester, byte[] bytes) {
        final long txTag = activeGeneration;
        return CompletableFuture.runAsync(() -> {
            assertRequesterActive(requester, "asyncSendData");
            doSendWrite(bytes, txTag);
        }, ioExecutor()).thenApply(v -> true).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw writeFailure(cause);
        });
    }

    /**
     * 写失败类型归一（asyncSendData 两路径共用）：SerialWriteException 原样向上抛
     * （保留写失败语义供调用方识别），其余异常包成通用 RuntimeException（旧行为兼容）。
     */
    private static RuntimeException writeFailure(Throwable cause) {
        if (cause instanceof SerialSourceClosedException) {
            return (SerialSourceClosedException) cause;
        }
        if (cause instanceof SerialWriteException) {
            return (SerialWriteException) cause;
        }
        return new RuntimeException("Failed to send data: " + cause.getMessage(), cause);
    }

    /**
     * 写发送体（域池 per-port 串行视图的 drain 任务体内执行）：写前自动重开 +
     * 写前清缓冲 + 非阻塞 write 反压检查 + CommTrace 帧捕获 + 残留写闸门（txTag 存活检查）。
     */
    private void doSendWrite(byte[] bytes, long txTag) {
        // 残留写闸门（F-43 清洗①）：被掐事务的补发命令在写口拒绝，不落端口
        assertWriteLive(txTag);
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
            lastSendTimeMs = System.currentTimeMillis();
            // 通讯帧捕获：写路径唯一出口；写失败只累计通道错误计数（不产生 TX 帧，如实）。
            // 权威归属=当前持锁 owner（io-resource-owner §5.2 捕获点）：doSendWrite 在持锁
            // 临界区内执行，直读 lockAcquireOwner 与锁状态同对象；锁外的手动写路径（如
            // teledyne sendLine 存量形态）owner 为 null，回落线程 MDC/回填现状（§7 边界）。
            if (written >= 0) {
                CommTraceBuffer.instance().tx(CommTraceTransport.SERIAL, serialInfo.portName, bytes,
                        null, lockAcquireOwner);
            } else {
                CommTraceBuffer.instance().error(CommTraceTransport.SERIAL, serialInfo.portName);
            }
            if (written < 0) {
                log.warn("[WRITE-FAIL] port={}, writeBytes 返 {} (非阻塞反压, 端口已被关闭), 写路径抛 SerialWriteException",
                        serialInfo.portName, written);
                throw new SerialWriteException("串口写入失败(writeBytes 返 " + written + ", 端口已被反压关闭): port=" + serialInfo.portName);
            }
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
            }, ioExecutor());
        } else {
            return readFromBufferBytes();
        }
    }

    /**
     * requester-aware 读取入口：物理读与缓冲读都在 IO task 体内复查，防止提交后、
     * 执行前 close 造成 zombie IO。
     */
    CompletableFuture<byte[]> asyncReadDataBytes(SerialSource requester) {
        if (isTestMode) {
            return CompletableFuture.supplyAsync(() -> {
                assertRequesterActive(requester, "asyncReadDataBytes");
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
        return readFromBufferBytes(requester);
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

    /**
     * 挂死端口自愈（Q-1/A2）：close + reopen 强拆挂死的本地阻塞 IO。
     *
     * <p>应用场景：jSerialComm {@code writeBytes} 是本地阻塞写（内核态持 fd），挂死时占用
     * per-port 单线程 IO 车道且自身无法自救。跨线程 {@code closePort()} 使阻塞在该 fd 上的
     * 写立即失败返回（jSerialComm closePort 线程安全），车道线程得救；随后 {@link #openPort}
     * 重开产出新 fd 供后续事务使用（写路径 {@code asyncSendData} 亦有写前自动重开兜底）。
     * 触发方 = 事务级硬超时（见 SerialTransactionStrategy），即「端口 IO 挂死」的强证据时刻。
     *
     * <p>退役门（bug-record 214000/103824）：若最后一个 source 已注销（对象已除名），本方法
     * 晚到几毫秒的重开是孤儿 fd 的诞生点——{@link #openPort} 入口的 retired 检查会拒绝重开，
     * 端口保持关闭（已除名对象的正确终态），同口新设备经新对象正常打开。
     *
     * @param reason 触发原因（日志定位用，如 transaction-hard-timeout）
     */
    void recoverWedgedPort(String reason) {
        log.error("[WEDGE-RECOVERY] port={}, 原因: {}, 强制 close+reopen 拆除挂死 IO",
                serialInfo.portName, reason);
        stopPolling();
        // Q-1/Q-2 二轮：物理 close+reopen 只救 fd，不清逻辑锁——若 currentKey 已成幽灵
        //（持锁事务的 release 链整体丢失），重开口后下一个 acquire 仍撞同一把逻辑锁，
        // 恢复循环不收敛。此处按持锁时长阈值强制清锁（与 acquire 入口同一收割状态机）。
        lock.lock();
        try {
            reapGhostLockIfStale("wedge-recovery");
        } finally {
            lock.unlock();
        }
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
        openPort(reason);
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
        //    mock/代理类名等源对象级信号由两族应答策略对端口实例判定）
        if (SerialTestSignals.testModeDeclared() || SerialTestSignals.junitPropertyPresent()) {
            return true;
        }

        // 2. 检查是否为 Mock（这个检测在实际创建对象时可能不适用）
        // 留给 DefaultResponseHandlerStrategy 进行更精确的检测

        return false;
    }
}
