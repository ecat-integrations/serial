package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceTransport;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SerialSourcePort manages the underlying serial port resource.
 * It is shared by multiple SerialSource instances that connect to the same port.
 * Package-private — not exposed to external callers.
 *
 * <p><b>锁入口使用规则（SDK 调度纪律）</b>：本类的 acquire/tryAcquire 只应有
 * {@link SerialTransactionStrategy} 的两个事务入口（及 serial-tcp-server 网关这类自管
 * 收发时序的框架型消费方）调用，集成设备代码不应直接取锁。两个入口是两种调度纪律而非冗余：
 * <ul>
 *   <li>命令/写事务 → {@code executeWithLambda}：阻塞排队等锁（waitQueue 有限等待）——
 *       命令的时效语义是「最终要执行」，等是正确的；</li>
 *   <li>周期轮询 → {@code executePolling}：tryAcquire 锁忙立即弃本轮（调度三原则
 *       「过期即弃」）——轮询数据过期即无价值，为等锁 park 只会把饥饿扩散到全系统
 *       （87 设备停摆事故的 E1/E2 形态）。</li>
 * </ul>
 * round/事务临界体内需要追加直发命令时，使用 SDK 注入的 source 直接 send（此时锁已持有，
 * 参照 gassensor PM3006SDevice 直发惯用法），勿再经事务入口二次取锁——同线程嵌套取锁是
 * 自死锁形态，由同线程嵌套守卫 fail-fast 立即抛（bug-record-20260829-082100 vaisala
 * 事故：旧形态静默 park 5h44m 无声失败）。
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
    /**
     * 持锁线程记账（36 号设计·同线程嵌套取锁守卫）：授予点记 {@code Thread} 引用——
     * 非线程名/线程 ID 字符串（线程 ID 复用会误判同线程）；release 与幽灵锁收割清 null。
     * 守卫判据 {@code Thread.currentThread() == lockHolderThread} 即同线程嵌套取锁。
     */
    private volatile Thread lockHolderThread;
    private final Queue<String> waitQueue = new LinkedList<>();

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

    // ========== Lock management ==========

    /**
     * 残留写闸门（F-43 清洗①，bugs/bug-record-20260826-093000 Q1）：per-port 事务代数。
     * acquire/tryAcquire 授予时递增 {@link #txGeneration} 并刷新 {@link #activeGeneration}；
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

    /** 授予新事务代数（acquire/tryAcquire 持锁临界区内调用）。 */
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
        return acquire(DEFAULT_ACQUIRE_WAIT_SECONDS, TimeUnit.SECONDS);
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
            reapGhostLockIfStale("acquire");
            assertNoSameThreadNestedAcquire("acquire");
            if (currentKey == null) {
                currentKey = requestKey;
                grantGeneration();
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                lockHolderThread = Thread.currentThread();
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
                            grantGeneration();
                            lockAcquireTime = System.currentTimeMillis();
                            lockAcquireThread = Thread.currentThread().getName();
                            lockHolderThread = Thread.currentThread();
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

    /** 轮询 tryAcquire 锁忙放弃计数（E2/R3 记账：放弃必须可观测，禁静默）。 */
    private final AtomicLong lockBusySkipCount = new AtomicLong();
    /** 锁忙放弃日志限频时间戳（volatile：锁临界区内读写，ReentrantLock 保证可见性，此字段仅日志用）。 */
    private volatile long lastBusySkipLogAt;
    /** 锁忙放弃日志限频间隔：默认 60s 一条（饱和期 ~30 次/min 的放弃若不限频会刷爆日志）。 */
    static final long BUSY_SKIP_LOG_INTERVAL_MS = 60_000L;

    /** 获取累计锁忙放弃次数（轮询 tryAcquire 因锁忙立即放弃的计数，运行时可观测用）。 */
    public long getLockBusySkipCount() {
        return lockBusySkipCount.get();
    }

    /**
     * 非阻塞获取锁（轮询专用，E2/R3 终态修复：调度三原则「过期即弃」）。
     *
     * <p>与 {@link #acquire(long, TimeUnit)} 的本质差异：锁忙时<b>不进 waitQueue、不 park
     * 等待、不消费 signal</b>，立即返回 null——本周期放弃，下周期再试。由此：
     * <ul>
     *   <li>轮询 worker 永不为等锁 park（秒级阻塞事务不再钉死调度 worker，杜绝
     *       6 worker × 87 设备互相排队的饱和震荡）；</li>
     *   <li>waitQueue 名额与 signal 唤醒完全留给写命令等有限等待路径，不互相干扰。</li>
     * </ul>
     *
     * <p>锁忙放弃有记账：累计计数 {@link #getLockBusySkipCount()} + 限频 warn 日志（饱和期
     * 可观测，禁静默）。幽灵锁收割检查与 {@link #acquire(long, TimeUnit)} 同入口复用。
     *
     * @return 锁标识；锁忙时立即返回 null（本周期放弃）
     */
    String tryAcquire() {
        String requestKey = generateRequestKey();
        lock.lock();
        try {
            reapGhostLockIfStale("acquire");
            assertNoSameThreadNestedAcquire("tryAcquire");
            if (currentKey == null) {
                currentKey = requestKey;
                grantGeneration();
                lockAcquireTime = System.currentTimeMillis();
                lockAcquireThread = Thread.currentThread().getName();
                lockHolderThread = Thread.currentThread();
                return requestKey;
            }
            long skips = lockBusySkipCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastBusySkipLogAt >= BUSY_SKIP_LOG_INTERVAL_MS) {
                lastBusySkipLogAt = now;
                log.warn("Polling tryAcquire skipped (lock busy): port={}, total skips={}, "
                        + "lock currently held by: {} (acquired at {} by thread {})",
                        getPortName(), skips, currentKey, lockAcquireTime, lockAcquireThread);
            }
            return null;
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
                lockHolderThread = null;
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

    /**
     * 幽灵锁收割（Q-1/Q-2 二轮根因修复）：持锁事务的事务级硬超时保证 release 必执行，
     * 但 release 的执行链本身可能整体丢失（超时任务入队被拒后 {@code withHardTimeout} 的计时
     * future 永不完成、持有线程被上游设备同步等待永久吸收等）——此时 currentKey 成为
     * 永久幽灵锁：后续所有 acquire 只能超时返回，端口永久瘫痪（live 实证幽灵锁持锁 45min+，
     * WEDGE-RECOVERY 反复触发不收敛）。本方法在 acquire 入口与 recoverWedgedPort 双点检查：
     * 持锁时长超过 {@link #ghostReapThresholdMs} 即按 release 同一状态机强制清零
     * （currentKey/lockAcquireTime/lockAcquireThread + signal 等待者），杜绝第二套清锁路径漂移。
     *
     * <p>必须在已持有 {@code lock} 的临界区内调用。收割不清空 waitQueue：等待者仍按
     * 队头接管规则被 signal 唤醒，超时者自行摘除（既有语义不变）。
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
                        + "按 release 同一状态机强制清零，等待者 {} 个被唤醒",
                serialInfo.portName, trigger, heldMs, ghostReapThresholdMs,
                currentKey, lockAcquireThread, waitQueue.size());
        // 与 release() 完全一致的状态清零 + 唤醒（同一状态机，无双路径漂移）
        currentKey = null;
        lockAcquireTime = 0;
        lockAcquireThread = null;
        lockHolderThread = null;
        if (!waitQueue.isEmpty()) {
            condition.signal();
        }
    }

    /**
     * 同线程嵌套取锁 fail-fast 守卫（36 号设计·方案 D 形态 A）：vaisala 事故
     * （bug-record-20260829-082100）的 SDK 层加固。事务体经 SerialTransactionStrategy
     * 的 executeHeld 在发起线程上同步执行——round/事务临界体内再经 executeWithLambda/
     * executePolling 二次取锁时，等待者与持有者是同一线程（等待 key 与持有 key 同为
     * 毫秒-线程ID），condition.await 永远等不到自己的 release：旧形态阻塞到超时返 null
     * （live 实证同一线程静默空转 5h44m，Acquire timeout 日志一直在却无人醒），守卫改为
     * 微秒级立即抛，错误直达根因。审计结论（36 号 §二）：20 仓全扫不存在合法的同线程
     * 嵌套取锁，无人依赖可重入。
     *
     * <p>位置约束：必须在 {@link #reapGhostLockIfStale} 之后——同线程的陈年幽灵锁应
     * 先收割后守卫，否则跨阈值的二次取锁会抛而非收割（破坏 acquireReapsGhostLock 契约）。
     *
     * <p>命中时锁状态原样不动：等锁者失败不影响既有持有关系（持锁者仍可正常 release）。
     *
     * @param entry 入口标识（acquire / tryAcquire，诊断定位用）
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
     *       （{@link #openPort} 内按新 serialInfo 全量重设参数并重启轮询）；
     *       共享此端口的全部 source 引用不变，随新 fd 继续工作；</li>
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
        log.warn("[RECONFIGURE-REOPEN] port={}, identity={}, 物理参数变化 [{}] -> [{}]，close+reopen 重建端口",
                serialInfo.portName, identity, serialInfo.settingsDescription(), newInfo.settingsDescription());
        stopPolling();
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
        serialInfo = newInfo;
        openPort(identity);
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
     * 写失败类型归一（asyncSendData 两路径共用）：SerialWriteException 原样向上抛
     * （保留写失败语义供调用方识别），其余异常包成通用 RuntimeException（旧行为兼容）。
     */
    private static RuntimeException writeFailure(Throwable cause) {
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
