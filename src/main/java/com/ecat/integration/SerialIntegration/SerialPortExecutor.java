package com.ecat.integration.SerialIntegration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.slf4j.MDC;

import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

/**
 * 每口一个的串行执行者（serial IO 最小模型 §3.1-§3.6）：单守护线程事件循环，替代旧
 * 「端口锁 + currentKey + 准入命令队列 + 授予回执 + 同线程守卫 + 幽灵锁收割 + 事务代数
 * 闸门 + 2×超时回放窗 + bufferLock + pollLock + lifecycleLock」九套事故沉积机制——
 * 严格串行使「一场对话一场对话地来」成为结构事实，不再用锁去模拟。
 *
 * <p><b>状态机</b>（主链 {@code IDLE → AWAIT_WRITE → AWAIT_RESPONSE → IDLE}；旁路
 * DRAINING / HELD / RECOVERING / CLOSED 终态）：一场对话从其第一个字节写出起独占端口，
 * 直到结束。全部状态迁移只发生在口执行线程（单写者，无锁）。
 *
 * <p><b>对话</b>（§3.2）：{@code {seq, submitNanos, session, owner, body/action, deadline,
 * priority}}。seq + submitNanos 仅用于日志与取消时识别主人。两种载体：body 对话
 * （设备事务体，轮到时在 IO 工作线程启动——口执行线程永不跑设备代码）与 HELD 对话
 * （同步 acquire/release = 持有者手动收尾的一场对话，deadline 同样生效，持有者线程
 * 死掉不会锁死口）。
 *
 * <p><b>有界优先级队列</b>（§3.5）：默认容量 3、每端口可配置（构造参数）；轮询入口 =
 * 普通，其余入口 = 高；满 → {@link PortQueueFullException}（写入口同步抛出，轮询入口
 * 异常完成其 future 落 isLockBusySkip 内化路径）；高优先级到达且满 → 挤掉最新排队的
 * 普通对话（其 future 以跳过异常完成），无可挤才抛满；只管排队顺序，绝不打断进行中的
 * 对话；同优先级先来后到。准入判断（满/挤出）在提交线程的小同步块内完成；
 * 排序结构的另一端（取队头开始下一场）只在口执行线程。
 *
 * <p><b>超时与排空</b>（§3.3，S5）：每场对话一个 deadline；异常结束（超时、强拆）后
 * 进入 DRAINING——丢弃全部到达字节直到线路静默 ≥ 静默间隔（默认 150ms，系统属性
 * {@value #DRAIN_SILENCE_PROPERTY} 可配置），排空总时长上限 = 该对话的事务超时（防流式
 * 设备无限排空）；静默确认后开下一场。正常结束不排空。硬超时看门狗在定时线程上
 * <b>直接跨线程 closePort</b> 强拆挂死 fd（口执行线程可能阻塞在挂死写上收不到事件），
 * 使阻塞写立即失败返回，随后事件驱动 RECOVERING 重开 → DRAINING → 队列续跑。
 *
 * <p><b>关闭语义</b>（§3.4）：session 关闭 → 该 session 未开始的对话以 closed 语义异常
 * 完成；进行中的对话跑到自然结束，结果只投给自己的 future；最后一份注销（退役）→
 * CLOSED 终态 + 单线程开闭（fd 的开与关只发生在口执行线程/看门狗），此后到达字节全丢，
 * 迟到 openPort 被拒——孤儿 fd 竞态无处发生。
 *
 * <p><b>观测缝</b>（§4 保留项）：seq 维度生命周期日志（入队/授予/写出/完成/超时/排空/
 * 挤出/丢弃原因），常规路径 debug、异常路径 warn/error。
 *
 * @author coffee
 */
final class SerialPortExecutor {

    private static final Log log = LogFactory.getLogger(SerialPortExecutor.class);

    /** 排空静默间隔系统属性键（S5：默认 150ms，可配置；非正值属配置错误，显式失败）。 */
    static final String DRAIN_SILENCE_PROPERTY = "ecat.serial.drainSilenceMs";
    /** 排空静默间隔默认值（毫秒）：真实设备应答尾巴的实测校准可在 P1 验收后调整。 */
    static final long DEFAULT_DRAIN_SILENCE_MS = 150L;

    private static final long drainSilenceMs = resolveDrainSilenceMs();

    private static long resolveDrainSilenceMs() {
        String raw = System.getProperty(DRAIN_SILENCE_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_DRAIN_SILENCE_MS;
        }
        long parsed = Long.parseLong(raw.trim());
        if (parsed <= 0) {
            throw new IllegalStateException(DRAIN_SILENCE_PROPERTY + " 必须为正整数（排空静默间隔毫秒），实际: " + raw);
        }
        return parsed;
    }

    /** 端口状态机（§3.1）。CLOSED 为终态：退役后的一切开/写请求被拒。 */
    enum PortState { IDLE, AWAIT_WRITE, AWAIT_RESPONSE, DRAINING, HELD, RECOVERING, CLOSED }

    /** 对话优先级（§3.5）：轮询入口=普通，其余入口（命令/写/acquire）=高。 */
    enum Priority { HIGH, NORMAL }

    /** 对话自身生命周期（口执行线程与看门狗读写，volatile 供看门狗判活）。 */
    private enum ConvState { QUEUED, RUNNING, DONE }

    /**
     * 一场对话（§3.2）。不可变面在提交线程构造；可变面（state/key/计时器）只由口执行
     * 线程迁移（state 另被看门狗 volatile 读作判活旁证）。result 是对话结果 future
     * （设备可见，正常完成点在 IO 工作线程、超时完成点在看门狗线程）；granted 是
     * HELD 对话的授予回执（acquire 同步等待目标）。
     */
    static final class Conversation {
        final long seq;
        final long submitNanos;
        final SerialSource session;
        final ResourceOwner owner;
        final Priority priority;
        /** 排队等待上限（毫秒）：普通对话=轮询预算（超时弃轮），高优先级=等锁预算（超时失败）。 */
        final long waitMs;
        /** 执行 deadline（毫秒，自授予起算）；HELD=持有上限；排空总时长上限同值（S5）。 */
        final long execTimeoutMs;
        /** 设备事务体（轮到时在 IO 工作线程启动）；held 与 portAction 互斥。 */
        final Function<SerialSource, CompletableFuture<Boolean>> body;
        /** 同步 acquire/release 承载的 HELD 对话（持有者手动收尾）。 */
        final boolean held;
        /** 口执行线程内联动作（reconfigure 重建等 fd 生命周期操作，禁止跑设备代码）。 */
        final Runnable portAction;
        /** 提交时捕获的 MDC（worker 上恢复，§3.6）。 */
        final Map<String, String> mdc;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final CompletableFuture<String> granted = new CompletableFuture<>();
        volatile ConvState state = ConvState.QUEUED;
        /** HELD 授予后由口执行线程赋值（acquire 契约的锁标识，release 凭它收尾）。 */
        volatile String key;
        ScheduledFuture<?> waitTimer;
        ScheduledFuture<?> execTimer;

        Conversation(long seq, SerialSource session, ResourceOwner owner, Priority priority,
                long waitMs, long execTimeoutMs, Function<SerialSource, CompletableFuture<Boolean>> body,
                boolean held, Runnable portAction, Map<String, String> mdc) {
            this.seq = seq;
            this.submitNanos = System.nanoTime();
            this.session = session;
            this.owner = owner;
            this.priority = priority;
            this.waitMs = waitMs;
            this.execTimeoutMs = execTimeoutMs;
            this.body = body;
            this.held = held;
            this.portAction = portAction;
            this.mdc = mdc;
        }
    }

    // ==================== 事件（口执行线程单一消费） ====================

    private abstract static class PortEvent {
    }

    /** 唤醒令牌：可能已有新对话可开始（多个并到无害，startNext 幂等）。 */
    private static final class Wakeup extends PortEvent {
    }

    /** 轮询线程读到的到达字节。 */
    private static final class OnBytes extends PortEvent {
        final byte[] data;
        final int length;

        OnBytes(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }
    }

    /** 写请求（asyncSendData 提交，口执行线程执行物理写——IO 发起是口执行线程职责）。 */
    private static final class WriteReq extends PortEvent {
        final SerialSource requester;
        final byte[] bytes;
        final CompletableFuture<Boolean> ack;

        WriteReq(SerialSource requester, byte[] bytes, CompletableFuture<Boolean> ack) {
            this.requester = requester;
            this.bytes = bytes;
            this.ack = ack;
        }
    }

    /** 应用层缓冲直读（asyncReadDataBytes 非测试路径）：缓冲归口执行线程私有后的读通道。 */
    private static final class BufferReadReq extends PortEvent {
        final CompletableFuture<byte[]> ack;

        BufferReadReq(CompletableFuture<byte[]> ack) {
            this.ack = ack;
        }
    }

    /** HELD 对话手动收尾（release；快照判定已由提交方完成）。 */
    private static final class ReleaseReq extends PortEvent {
        final String key;

        ReleaseReq(String key) {
            this.key = key;
        }
    }

    /** 排队等待超时（提交点计时）：仍在队则按优先级弃轮/失败。 */
    private static final class WaitExpired extends PortEvent {
        final Conversation conv;

        WaitExpired(Conversation conv) {
            this.conv = conv;
        }
    }

    /** 对话 deadline 到点（看门狗线程已跨线程强拆 fd 并异常完成 future）。 */
    private static final class DeadlineHit extends PortEvent {
        final Conversation conv;

        DeadlineHit(Conversation conv) {
            this.conv = conv;
        }
    }

    /** 排空静默确认：线路静默期满，开下一场。 */
    private static final class DrainSilenceElapsed extends PortEvent {
    }

    /** 排空总时长上限到点（防流式设备无限排空，S5）。 */
    private static final class DrainCapElapsed extends PortEvent {
    }

    /** 新 session 注册（端口重开检查）。 */
    private static final class SessionRegistered extends PortEvent {
        final String identity;
        final CompletableFuture<Void> ack;

        SessionRegistered(String identity, CompletableFuture<Void> ack) {
            this.identity = identity;
            this.ack = ack;
        }
    }

    /** session 注销（非最后一份）：取消其未开始的对话（§3.4）。 */
    private static final class SessionLeft extends PortEvent {
        final SerialSource source;

        SessionLeft(SerialSource source) {
            this.source = source;
        }
    }

    /** 退役（最后一份注销）：CLOSED 终态 + 撤轮询 + 关 fd + 取消全部未开始对话。 */
    private static final class Retire extends PortEvent {
        final CompletableFuture<Void> ack;

        Retire(CompletableFuture<Void> ack) {
            this.ack = ack;
        }
    }

    /** 外部恢复请求（recoverWedgedPort：modbus RTU 直持流等的事务硬超时路径）。 */
    private static final class RecoverReq extends PortEvent {
        final String reason;

        RecoverReq(String reason) {
            this.reason = reason;
        }
    }

    /** 强拆当前在飞对话并排空（markTransactionAborted）。 */
    private static final class AbortReq extends PortEvent {
        final String reason;

        AbortReq(String reason) {
            this.reason = reason;
        }
    }

    /** 对话体在 IO 工作线程完成（结果已投递），请求口执行线程收尾。 */
    private static final class BodyDone extends PortEvent {
        final Conversation conv;

        BodyDone(Conversation conv) {
            this.conv = conv;
        }
    }

    /** 监听器注册时的缓冲回放请求（写前清缓冲 + 排空使回放天然不跨对话，2×回放窗退役）。 */
    private static final class ReplayReq extends PortEvent {
        final SerialDataListener listener;

        ReplayReq(SerialDataListener listener) {
            this.listener = listener;
        }
    }

    /** 测试确定性屏障：此前入队的全部事件已处理完毕的标记（仅测试提交）。 */
    private static final class Probe extends PortEvent {
        final CompletableFuture<Void> ack;

        Probe(CompletableFuture<Void> ack) {
            this.ack = ack;
        }
    }

    // ==================== 状态（迁移只在口执行线程；volatile 面供提交/看门狗读） ====================

    private final String portName;
    private final int queueCapacity;
    private final SerialSourcePort port;

    private final Object admissionLock = new Object();
    private final ArrayDeque<Conversation> highQueue = new ArrayDeque<>();
    private final ArrayDeque<Conversation> normalQueue = new ArrayDeque<>();

    private final LinkedBlockingQueue<PortEvent> events = new LinkedBlockingQueue<>();
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicLong lockBusySkipCount = new AtomicLong();

    private volatile PortState state = PortState.IDLE;
    private volatile Conversation current;
    private volatile Thread worker;

    /** 锁忙跳过日志限频时间戳（真弃轮会刷屏，60s 一条）。 */
    private static final long BUSY_SKIP_LOG_INTERVAL_MS = 60_000L;
    private volatile long lastBusySkipLogAt;

    /** 排空窗内丢弃字节记账（drain-end 日志用，口执行线程独占）。 */
    private long droppedDuringDrain;
    /** 挂起的恢复请求（在飞/排空中收到 → 收场后执行）。 */
    private String pendingRecoverReason;
    private ScheduledFuture<?> drainSilenceTimer;
    private ScheduledFuture<?> drainCapTimer;
    SerialPortExecutor(SerialSourcePort port, String portName, int queueCapacity) {
        this.port = port;
        this.portName = portName;
        this.queueCapacity = queueCapacity;
    }

    // ==================== 提交面（调用线程；准入小同步块） ====================

    /**
     * 提交设备事务对话（executeWithLambda/executePolling 入队化入口）。
     *
     * @throws PortQueueFullException 队满且无可挤（写入口同步抛出契约，§3.5）
     * @throws SerialSourceClosedException 端口已退役（CLOSED 终态拒收）
     */
    Conversation submitBody(SerialSource session, ResourceOwner owner, Priority priority,
            long waitMs, long execTimeoutMs,
            Function<SerialSource, CompletableFuture<Boolean>> body) {
        Conversation conv = new Conversation(seqGen.incrementAndGet(), session, owner, priority,
                waitMs, execTimeoutMs, body, false, null, MDC.getCopyOfContextMap());
        admitAndScheduleWaitTimer(conv);
        log.debug("[CONV] port={}, seq={}, 入队 priority={} wait={}ms execTimeout={}ms queued={}",
                portName, conv.seq, priority, waitMs, execTimeoutMs, queuedCount());
        return conv;
    }

    /**
     * 提交 HELD 对话（同步 acquire / 轮询有界取口的承载）：授予回执经 {@code granted}；
     * 队满返回 null（acquire 契约「null=超时或队列满」，不抛）。优先级按入口语义：
     * acquire=高（§3.5 其余入口），acquirePollingBounded=普通（轮询入口，弃轮走
     * LockBusySkippedException 记账语义）。
     */
    Conversation trySubmitHeld(SerialSource session, ResourceOwner owner, Priority priority,
            long waitMs, long holdTimeoutMs) {
        Conversation conv = new Conversation(seqGen.incrementAndGet(), session, owner, priority,
                waitMs, holdTimeoutMs, null, true, null, MDC.getCopyOfContextMap());
        try {
            admitAndScheduleWaitTimer(conv);
        } catch (PortQueueFullException full) {
            log.debug("[CONV] port={}, HELD 入队拒绝（队满）: {}", portName, full.getMessage());
            return null;
        } catch (SerialSourceClosedException closed) {
            return null;
        }
        log.debug("[CONV] port={}, seq={}, HELD 入队 wait={}ms hold={}ms queued={}",
                portName, conv.seq, waitMs, holdTimeoutMs, queuedCount());
        return conv;
    }

    /**
     * 提交口执行线程内联动作对话（reconfigure 重建等 fd 生命周期操作）：轮到时在口执行
     * 线程执行 {@code action}（句柄开/关只在口执行线程发生）。
     *
     * @throws PortQueueFullException 队满且无可挤
     */
    Conversation submitAction(SerialSource session, long waitMs, Runnable action) {
        Conversation conv = new Conversation(seqGen.incrementAndGet(), session, null, Priority.HIGH,
                waitMs, waitMs, null, false, action, MDC.getCopyOfContextMap());
        admitAndScheduleWaitTimer(conv);
        log.debug("[CONV] port={}, seq={}, 端口动作入队 wait={}ms queued={}",
                portName, conv.seq, waitMs, queuedCount());
        return conv;
    }

    /**
     * 准入本体（提交线程小同步块，§3.5）：容量内入队；满时高优先级挤最新排队的普通对话
     * （其 future 以 {@link PortQueueFullException} 跳过异常完成）；无可挤抛满。
     * 排队等待计时器在入队后于提交线程挂载（超时由口执行线程经 WaitExpired 事件收敛）。
     */
    private void admitAndScheduleWaitTimer(Conversation conv) {
        synchronized (admissionLock) {
            if (state == PortState.CLOSED) {
                throw new SerialSourceClosedException("conversation submit", portName,
                        conv.session != null ? conv.session.getIdentity() : "port-retired");
            }
            if (highQueue.size() + normalQueue.size() >= queueCapacity) {
                if (conv.priority == Priority.HIGH) {
                    Conversation victim = normalQueue.pollLast();
                    if (victim != null) {
                        evict(victim);
                    } else {
                        throw new PortQueueFullException("端口对话队列满（容量 " + queueCapacity
                                + "）且全部为高优先级，无可挤: port=" + portName);
                    }
                } else {
                    recordPollingSkip("queue full");
                    throw new PortQueueFullException("端口对话队列满（容量 " + queueCapacity
                            + "）: port=" + portName + ", priority=" + conv.priority);
                }
            }
            if (conv.priority == Priority.HIGH) {
                highQueue.addLast(conv);
            } else {
                normalQueue.addLast(conv);
            }
        }
        scheduleWaitTimer(conv);
        events.add(new Wakeup());
        ensureThreadStarted();
    }

    /** 被挤对话收尾（跳过语义异常完成 + 真弃轮记账，观测不说谎）。 */
    private void evict(Conversation victim) {
        cancel(victim.waitTimer);
        victim.state = ConvState.DONE;
        victim.result.completeExceptionally(new PortQueueFullException("高优先级对话到达，挤出排队中的轮询对话: port="
                + portName + ", 被挤 seq=" + victim.seq));
        recordPollingSkip("evicted by high-priority arrival");
        log.warn("[CONV] port={}, seq={}, 被高优先级对话挤出（跳过异常已投递）", portName, victim.seq);
    }

    private void scheduleWaitTimer(final Conversation conv) {
        try {
            conv.waitTimer = SerialTimeoutScheduler.schedule(
                    () -> events.add(new WaitExpired(conv)), conv.waitMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException scheduleEx) {
            // 计时提交被拒（调度器饱和/停机）：无计时器的排队对话失去超时收敛，立即离队
            // 异常完成（与旧 withHardTimeout 同款 fail-fast，杜绝不可终结对话）
            removeQueued(conv);
            conv.state = ConvState.DONE;
            conv.result.completeExceptionally(new IllegalStateException(
                    "Wait-timeout timer submission failed, failing fast", scheduleEx));
        }
    }

    /** 提交物理写（asyncSendData 承载）：返回写结果 future（成功 true / 失败异常完成）。 */
    CompletableFuture<Boolean> submitWrite(SerialSource requester, byte[] bytes) {
        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        if (state == PortState.CLOSED) {
            // 终态快速拒绝（口执行线程已退出，事件无人消费）
            ack.completeExceptionally(new SerialWriteException("串口已退役（终态），写入拒绝: port=" + portName));
            return ack;
        }
        events.add(new WriteReq(requester, bytes, ack));
        ensureThreadStarted();
        return ack;
    }

    /** 提交应用层缓冲直读（asyncReadDataBytes 非测试路径）。 */
    CompletableFuture<byte[]> submitBufferRead() {
        CompletableFuture<byte[]> ack = new CompletableFuture<>();
        if (state == PortState.CLOSED) {
            // 终态：口执行线程已退出，缓冲冻结，快照直读（无并发写者）
            ack.complete(port.readAndClearReceiveBuffer());
            return ack;
        }
        events.add(new BufferReadReq(ack));
        ensureThreadStarted();
        return ack;
    }

    /** 提交到达字节事件（轮询线程调用，O(1) 即返永不阻塞）。 */
    void submitBytes(byte[] data, int length) {
        if (state == PortState.CLOSED) {
            return; // 退役后到达字节全丢（§3.4；口执行线程已退出）
        }
        events.add(new OnBytes(data, length));
        ensureThreadStarted();
    }

    /** 提交监听器注册时的缓冲回放请求。 */
    void submitReplay(SerialDataListener listener) {
        events.add(new ReplayReq(listener));
        ensureThreadStarted();
    }

    /** 提交恢复请求（recoverWedgedPort）。 */
    void submitRecover(String reason) {
        if (state == PortState.CLOSED) {
            // 终态：迟到自愈的重开在入口被拒（[OPEN-REJECTED]，孤儿 fd 防线单点）；
            // 口执行线程已退出，拒绝在提交线程同步落地（纯检查无 fd 触碰）
            port.openPortNow(reason);
            return;
        }
        events.add(new RecoverReq(reason));
        ensureThreadStarted();
    }

    /** 提交强拆请求（markTransactionAborted）。 */
    void submitAbort(String reason) {
        if (state == PortState.CLOSED) {
            return; // 终态无在飞对话可强拆
        }
        events.add(new AbortReq(reason));
        ensureThreadStarted();
    }

    /** session 注册事件（fd 重开检查在口执行线程；调用线程有界等待开/查完成再返回）。 */
    void sessionRegistered(String identity) {
        CompletableFuture<Void> ack = new CompletableFuture<>();
        events.add(new SessionRegistered(identity, ack));
        ensureThreadStarted();
        awaitEventAck(ack, "session register open-check");
    }

    /** session 注销事件（非最后一份）：取消其未开始的对话。 */
    void sessionLeft(SerialSource source) {
        events.add(new SessionLeft(source));
        ensureThreadStarted();
    }

    /**
     * 退役事件（最后一份注销，地图摘除已由调用方同步完成）：CLOSED 终态。调用线程有界
     * 等待退役处理完成再返回（closePort 返回时 fd 已关/撤轮询已完成——与旧同步 closePort
     * 的可观测语义一致）；口执行线程被挂死写钉死时等待超时放行，事件将被随后处理
     * （看门狗跨线程强拆救活口执行线程后即完成退役）。
     */
    void retire() {
        CompletableFuture<Void> ack = new CompletableFuture<>();
        events.add(new Retire(ack));
        ensureThreadStarted();
        awaitEventAck(ack, "port retire");
    }

    /** 事件回执的有界等待（注册/退役的可观测语义保序；超时放行不吞——事件仍会被处理）。 */
    private void awaitEventAck(CompletableFuture<Void> ack, String what) {
        try {
            ack.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[CONV] port={}, {} 等待超时（口执行线程阻塞?），先行返回——事件将被随后处理", portName, what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(what + " event failed unexpectedly", e.getCause());
        }
    }

    /**
     * HELD 手动收尾请求（release，调用线程非阻塞）：同步快照判定 key 是否匹配当前 HELD
     * 对话——匹配则投递收尾事件并返回 true（实际收场在口执行线程，微秒级）；不匹配立即
     * false（含「已持有超时被看门狗收场」的竞速，持有者意图已达成，口已继续）。
     */
    boolean requestRelease(String key) {
        synchronized (admissionLock) {
            Conversation conv = current;
            if (conv != null && conv.held && conv.key != null && conv.key.equals(key)) {
                events.add(new ReleaseReq(key));
                ensureThreadStarted();
                return true;
            }
        }
        log.warn("[CONV] port={}, 无效 release: key={}（当前在飞={}）", portName, key, describeCurrentHeld());
        return false;
    }

    private String describeCurrentHeld() {
        Conversation conv = current;
        if (conv == null) {
            return "none";
        }
        return "seq=" + conv.seq + " held=" + conv.held + " key=" + conv.key + " state=" + conv.state;
    }

    /**
     * 等待方超时/中断离队：仍在队则摘除并异常完成（授予竞速赢家已离队时为 no-op）。
     */
    void abandonQueued(Conversation conv, String reason) {
        if (removeQueued(conv)) {
            cancel(conv.waitTimer);
            conv.state = ConvState.DONE;
            conv.result.completeExceptionally(new IllegalStateException(reason));
            log.debug("[CONV] port={}, seq={}, 等待方离队（{}）", portName, conv.seq, reason);
        }
    }

    // ==================== 观测缝（测试与 CommTrace 捕获点） ====================

    /** 当前在飞对话的归属（CommTrace TX/RX 捕获点读；无对话为 null）。 */
    ResourceOwner currentOwner() {
        Conversation conv = current;
        return conv != null ? conv.owner : null;
    }

    /** 端口状态（提交面 CLOSED 判定与测试断言用；volatile 读）。 */
    PortState state() {
        return state;
    }

    /** 排队对话数（高+普通，不含在飞；与 state()==IDLE 成对可判断口是否全空）。 */
    int queuedCount() {
        synchronized (admissionLock) {
            return highQueue.size() + normalQueue.size();
        }
    }

    /** 是否有在飞对话（HELD 授予/body 派发/排空/恢复都算占用；测试断言用）。 */
    boolean hasInFlightConversation() {
        return current != null || state == PortState.DRAINING || state == PortState.RECOVERING;
    }

    /** 累计轮询真弃轮计数（队满被拒/挤出/等待预算耗尽；等到口的轮次不计，观测不说谎）。 */
    long getLockBusySkipCount() {
        return lockBusySkipCount.get();
    }

    /**
     * 事件循环确定性屏障（测试缝）：本调用之前入队的全部事件已处理完才返回——RX/回放/
     * 写等异步路径的测试断言点（禁 sleep 猜测，验证事件已发生）。生产禁用。
     */
    void awaitEventLoopForTest() throws InterruptedException {
        if (state == PortState.CLOSED) {
            return; // 终态：口执行线程已退出，无事件可再处理
        }
        CompletableFuture<Void> ack = new CompletableFuture<>();
        events.add(new Probe(ack));
        ensureThreadStarted();
        try {
            ack.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Probe event failed unexpectedly", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("口执行线程 10s 内未处理到屏障事件（事件循环卡死?）: port=" + portName);
        }
    }

    /** 红测注入口：缩短排空静默间隔（仅同包测试使用；生产用系统属性/默认 150ms）。 */
    void setDrainSilenceMsForTest(long silenceMs) {
        if (silenceMs <= 0) {
            throw new IllegalArgumentException("drainSilenceMs must be > 0, got: " + silenceMs);
        }
        drainSilenceMsOverride = silenceMs;
    }

    /** 排空静默间隔生效值（实例覆盖优先于静态配置）。 */
    private long drainSilenceMsOverride;

    private long silenceMs() {
        return drainSilenceMsOverride > 0 ? drainSilenceMsOverride : drainSilenceMs;
    }

    // ==================== 口执行线程（事件循环） ====================

    private void ensureThreadStarted() {
        if (worker != null) {
            return;
        }
        synchronized (this) {
            if (worker != null || state == PortState.CLOSED) {
                return;
            }
            Thread created = new NamedThreadFactory("ecat-serial-port-" + portName, true).newThread(this::loop);
            worker = created;
            created.start();
        }
    }

    private void loop() {
        for (;;) {
            PortEvent event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                handle(event);
            } catch (Throwable t) {
                // 单事件异常不得杀事件循环（执行者是公共仲裁域）：如实记账后续跑
                log.error("[CONV] port={}, 事件处理异常（执行者续跑）: {}", portName, t.getMessage(), t);
            }
            if (state == PortState.CLOSED && current == null && queuesEmpty()) {
                // 终态且无在飞：线程使命完成即退出（重建/重注册走全新端口对象，不积线程）
                log.info("[CONV] port={}, 已退役且队列清空，口执行线程退出", portName);
                return;
            }
        }
    }

    private void handle(PortEvent event) {
        if (event instanceof Wakeup) {
            startNext();
        } else if (event instanceof OnBytes) {
            OnBytes bytes = (OnBytes) event;
            handleBytes(bytes.data, bytes.length);
        } else if (event instanceof WriteReq) {
            handleWrite((WriteReq) event);
        } else if (event instanceof BufferReadReq) {
            ((BufferReadReq) event).ack.complete(port.readAndClearReceiveBuffer());
        } else if (event instanceof ReleaseReq) {
            handleRelease((ReleaseReq) event);
        } else if (event instanceof WaitExpired) {
            handleWaitExpired(((WaitExpired) event).conv);
        } else if (event instanceof DeadlineHit) {
            handleDeadline(((DeadlineHit) event).conv);
        } else if (event instanceof DrainSilenceElapsed) {
            if (state == PortState.DRAINING) {
                exitDrain("silence-confirmed");
            }
        } else if (event instanceof DrainCapElapsed) {
            if (state == PortState.DRAINING) {
                log.warn("[CONV] port={}, 排空静默未确认（流式回灌?），达上限强制开下一场", portName);
                exitDrain("cap-forced");
            }
        } else if (event instanceof SessionRegistered) {
            SessionRegistered registered = (SessionRegistered) event;
            if (state != PortState.CLOSED) {
                port.openPortNow(registered.identity);
            }
            registered.ack.complete(null);
        } else if (event instanceof SessionLeft) {
            cancelQueuedOfSession(((SessionLeft) event).source);
        } else if (event instanceof Retire) {
            handleRetire();
            ((Retire) event).ack.complete(null);
        } else if (event instanceof RecoverReq) {
            handleRecover(((RecoverReq) event).reason);
        } else if (event instanceof AbortReq) {
            handleAbort(((AbortReq) event).reason);
        } else if (event instanceof BodyDone) {
            handleBodyDone(((BodyDone) event).conv);
        } else if (event instanceof ReplayReq) {
            handleReplay(((ReplayReq) event).listener);
        } else if (event instanceof Probe) {
            ((Probe) event).ack.complete(null);
        }
    }

    // ==================== 对话启动/收尾 ====================

    /** 取队头开下一场（只管排序，绝不打断进行中对话）。 */
    private void startNext() {
        if (state != PortState.IDLE || current != null) {
            return;
        }
        Conversation conv = pollHead();
        while (conv != null && conv.state != ConvState.QUEUED) {
            // 已被取消/挤出的竞速输家：跳过取下一位
            conv = pollHead();
        }
        if (conv == null) {
            return;
        }
        cancel(conv.waitTimer);
        conv.waitTimer = null;
        conv.state = ConvState.RUNNING;
        current = conv;
        if (conv.portAction != null) {
            runPortAction(conv);
        } else if (conv.held) {
            state = PortState.HELD;
            conv.key = "c" + conv.seq;
            log.debug("[CONV] port={}, seq={}, HELD 授予（key={}，持有上限 {}ms）",
                    portName, conv.seq, conv.key, conv.execTimeoutMs);
            conv.granted.complete(conv.key);
            scheduleDeadlineTimer(conv, false);
        } else {
            state = PortState.AWAIT_WRITE;
            log.debug("[CONV] port={}, seq={}, 开始（dispatch 到 IO 工作线程，deadline {}ms）",
                    portName, conv.seq, conv.execTimeoutMs);
            scheduleDeadlineTimer(conv, true);
            dispatchBody(conv);
        }
    }

    /** 端口动作对话（fd 生命周期操作）：口执行线程内联执行，禁止设备代码。 */
    private void runPortAction(Conversation conv) {
        try {
            conv.portAction.run();
            conv.result.complete(Boolean.TRUE);
        } catch (Throwable t) {
            conv.result.completeExceptionally(t);
        }
        finishConversation(conv, false);
    }

    /**
     * 对话体派发（§3.6 线程模型）：设备 lambda 在 IO 工作线程（SerialIoPool 域池）启动；
     * 设备可见 future 只在 IO 工作线程完成（正常路径）；MDC 提交点捕获、worker 上恢复。
     * 口执行线程只做排序与 IO 发起，永不执行设备代码。
     */
    private void dispatchBody(final Conversation conv) {
        final Map<String, String> mdc = conv.mdc;
        try {
            SerialIoPool.domainExecutor().execute(() -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    CompletableFuture<Boolean> ops = conv.body.apply(conv.session);
                    ops.whenComplete((Boolean res, Throwable ex) -> {
                        // 正常完成点=IO 工作线程（§3.6）；超时抢先完成的场景此处 no-op
                        if (ex != null) {
                            conv.result.completeExceptionally(ex);
                        } else {
                            conv.result.complete(res);
                        }
                        events.add(new BodyDone(conv));
                    });
                } catch (Throwable t) {
                    // 事务体同步抛出：对话立即异常完成（旧 executeHeld 同语义），线路未写不排空
                    conv.result.completeExceptionally(t);
                    events.add(new BodyDone(conv));
                } finally {
                    if (mdc != null) {
                        if (previous != null) {
                            MDC.setContextMap(previous);
                        } else {
                            MDC.clear();
                        }
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            // IO 池饱和/停机（显式拒绝非静默）：对话立即异常完成，口继续下一场
            conv.result.completeExceptionally(rejected);
            finishConversation(conv, false);
        }
    }

    /**
     * 对话 deadline 看门狗（SerialTimeoutScheduler 线程）：口执行线程可能阻塞在挂死写上
     * 收不到事件，故三步在<b>看门狗线程</b>直接执行——①跨线程 closePort 强拆挂死 fd
     * （jSerialComm closePort 线程安全，阻塞写立即失败返回，口执行线程得救；HELD 对话
     * 无 fd 挂死证据，不强拆）；②异常完成对话 future（保证必然终结）；③投递状态迁移
     * 事件。三步各自幂等：closePort 重复安全，result.complete 先到先得。
     */
    private void scheduleDeadlineTimer(final Conversation conv, boolean closeFdOnFire) {
        final boolean closeFd = closeFdOnFire;
        final long timeoutMs = conv.execTimeoutMs;
        Runnable watchdog = () -> {
            if (closeFd && conv.state == ConvState.RUNNING && port.isFdOpen()) {
                port.closeFd("[transaction-hard-timeout]");
            }
            conv.result.completeExceptionally(new TimeoutException(
                    (conv.held ? "HELD conversation hold" : "Transaction hard") + " timeout after "
                            + timeoutMs + " ms"));
            events.add(new DeadlineHit(conv));
        };
        try {
            conv.execTimer = SerialTimeoutScheduler.schedule(watchdog, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException scheduleEx) {
            // 计时任务提交失败（调度器饱和/停机）：与旧 withHardTimeout 同款 fail-fast——
            // 立即异常完成保证对话必然终结，否则口执行线程永等、口瘫痪
            conv.result.completeExceptionally(new IllegalStateException(
                    "Hard-timeout timer submission failed, failing fast to guarantee conversation completion",
                    scheduleEx));
            events.add(new DeadlineHit(conv));
        }
    }

    private void handleDeadline(Conversation conv) {
        if (current != conv || conv.state != ConvState.RUNNING) {
            return; // 陈年计时器（对话已正常收尾）
        }
        if (conv.held) {
            log.warn("[CONV] port={}, seq={}, HELD 持有超时（{}ms 未 release），强制收场并排空"
                    + "（持有者线程消亡不锁死口）", portName, conv.seq, conv.execTimeoutMs);
        } else {
            log.error("[CONV] port={}, seq={}, 事务硬超时（{}ms），fd 已由看门狗跨线程强拆，重开并排空",
                    portName, conv.seq, conv.execTimeoutMs);
        }
        finishConversation(conv, true);
    }

    private void handleBodyDone(Conversation conv) {
        if (current != conv || conv.state != ConvState.RUNNING) {
            return; // deadline 已收尾（结果只投给自己的 future，不重复收尾）
        }
        finishConversation(conv, false);
    }

    /**
     * 对话收尾：正常结束（收到应答/持有者 release/同步抛出/动作完成）不排空；异常结束
     * （超时/强拆）排空（§3.3），body 对话经 RECOVERING 重开（fd 已被看门狗强拆），
     * HELD 超时只排空。
     */
    private void finishConversation(Conversation conv, boolean abnormal) {
        cancel(conv.execTimer);
        conv.execTimer = null;
        if (current == conv) {
            current = null;
        }
        conv.state = ConvState.DONE;
        if (!abnormal || state == PortState.CLOSED) {
            state = PortState.IDLE;
            runPendingRecoverIfAny();
            startNext();
            return;
        }
        enterDrain(!conv.held, conv);
    }

    // ==================== 排空（§3.3 S5） ====================

    /**
     * 进入排空：丢弃全部到达字节直到线路静默 ≥ 静默间隔（新到达字节重置静默计时）；
     * 排空总时长上限 = 该对话的事务超时（防流式设备无限排空）。body 对话先经 RECOVERING
     * 重开（fd 已被看门狗强拆，重开含 RX 清洗）。
     */
    private void enterDrain(boolean reopen, Conversation finished) {
        if (reopen) {
            state = PortState.RECOVERING;
            port.stopPolling();
            try {
                port.openPortNow("[drain-reopen]");
            } catch (RuntimeException openFailed) {
                // 重开失败（如无效口名/口不可用）不得滞留状态机：落错误后照常进入排空，
                // 后续写路径的写前自动重开 + 看门狗仍是恢复网（与旧 recoverWedgedPort 的
                // open 失败后端口保持关闭、写时再试语义等价）
                log.error("[CONV] port={}, seq={}, 排空重开失败（{}），端口保持关闭，写路径自动重开兜底",
                        portName, finished != null ? finished.seq : -1, openFailed.getMessage());
            }
        }
        state = PortState.DRAINING;
        droppedDuringDrain = 0;
        port.clearReceiveBuffer();
        long capMs = finished != null ? finished.execTimeoutMs : silenceMs();
        log.debug("[CONV] port={}, seq={}, 进入排空（静默 {}ms，上限 {}ms）",
                portName, finished != null ? finished.seq : -1, silenceMs(), capMs);
        try {
            drainSilenceTimer = SerialTimeoutScheduler.schedule(
                    () -> events.add(new DrainSilenceElapsed()), silenceMs(), TimeUnit.MILLISECONDS);
            drainCapTimer = SerialTimeoutScheduler.schedule(
                    () -> events.add(new DrainCapElapsed()), capMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException scheduleEx) {
            // 排空计时提交被拒：立即结束排空（不静默卡死队列）
            log.warn("[CONV] port={}, 排空计时提交失败，直接开下一场: {}", portName, scheduleEx.getMessage());
            exitDrain("drain-timer-rejected");
        }
    }

    private void exitDrain(String how) {
        cancel(drainSilenceTimer);
        cancel(drainCapTimer);
        drainSilenceTimer = null;
        drainCapTimer = null;
        log.debug("[CONV] port={}, 排空结束（{}），丢弃 {} B", portName, how, droppedDuringDrain);
        droppedDuringDrain = 0;
        state = PortState.IDLE;
        runPendingRecoverIfAny();
        startNext();
    }

    private void handleBytes(byte[] data, int length) {
        if (state == PortState.CLOSED) {
            return; // 退役后到达字节全丢（§3.4）
        }
        if (state == PortState.DRAINING) {
            // 排空窗内到达字节 = 异常结束对话的迟到应答/残留：丢弃（遗嘱消息的结构性根除）
            droppedDuringDrain += length;
            rescheduleDrainSilence();
            return;
        }
        // 帧事件：权威归属=当前对话 owner（无对话为 null，落既有回填/如实语义）
        port.traceRx(data, length, currentOwner());
        port.appendReceiveBuffer(data, length);
        port.notifySources(data, length);
    }

    /** 排空中新字节到达 = 线路尚不静默：重置静默计时（总上限计时器不动）。 */
    private void rescheduleDrainSilence() {
        cancel(drainSilenceTimer);
        try {
            drainSilenceTimer = SerialTimeoutScheduler.schedule(
                    () -> events.add(new DrainSilenceElapsed()), silenceMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException scheduleEx) {
            log.warn("[CONV] port={}, 排空静默重挂失败（上限计时器兜底）: {}", portName, scheduleEx.getMessage());
        }
    }

    // ==================== HELD 收尾 ====================

    private void handleRelease(ReleaseReq req) {
        Conversation conv = current;
        if (conv != null && conv.held && conv.key != null && conv.key.equals(req.key)
                && conv.state == ConvState.RUNNING) {
            log.debug("[CONV] port={}, seq={}, HELD 释放（持有 {}ms）", portName, conv.seq,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - conv.submitNanos));
            if (!conv.result.isDone()) {
                conv.result.complete(Boolean.TRUE);
            }
            finishConversation(conv, false);
        } else {
            // 持有超时看门狗先收场的竞速：持有者意图已达成（快照已答 true），如实记 debug
            log.debug("[CONV] port={}, release 事件到时对话已收场（持有超时竞速）: key={}", portName, req.key);
        }
    }

    // ==================== 排队等待超时 ====================

    private void handleWaitExpired(Conversation conv) {
        if (conv.state != ConvState.QUEUED || !removeQueued(conv)) {
            return; // 已开始/已收尾/已被挤出：陈年计时器或竞速赢家
        }
        conv.state = ConvState.DONE;
        cancel(conv.waitTimer);
        if (conv.priority == Priority.NORMAL) {
            recordPollingSkip("wait budget " + conv.waitMs + "ms exhausted");
            conv.result.completeExceptionally(new LockBusySkippedException(
                    "Polling transaction skipped: lock unavailable within budget " + conv.waitMs
                            + "ms, will retry next cycle"));
            log.debug("[CONV] port={}, seq={}, 轮询等待预算耗尽弃轮", portName, conv.seq);
        } else {
            conv.result.completeExceptionally(new IllegalStateException("Failed to acquire lock"));
            log.debug("[CONV] port={}, seq={}, 等锁预算耗尽失败", portName, conv.seq);
        }
        if (conv.held && !conv.granted.isDone()) {
            conv.granted.cancel(false);
        }
    }

    // ==================== 写（IO 发起在口执行线程） ====================

    /**
     * 物理写（口执行线程）：写前自动重开 + 写前清缓冲（缓冲归口执行线程私有）+ 非阻塞
     * 写反压检查 + CommTrace 帧捕获 + AWAIT_WRITE→AWAIT_RESPONSE 迁移。排空/恢复/终态
     * 拒写（被掐对话的残留补发防线，等价旧事务代数闸门的实际防护窗）。
     */
    private void handleWrite(WriteReq req) {
        // requester 终检（共享物理资源的不可回退边界）：提交时视图尚活、执行前已关闭的
        // 排队写在真正触碰端口前拒绝（zombie IO 防线）
        if (req.requester != null && req.requester.isViewClosed()) {
            req.ack.completeExceptionally(new SerialSourceClosedException("asyncSendData",
                    portName, req.requester.getIdentity()));
            return;
        }
        if (state == PortState.CLOSED) {
            req.ack.completeExceptionally(new SerialWriteException("串口已退役（终态），写入拒绝: port=" + portName));
            return;
        }
        if (state == PortState.DRAINING || state == PortState.RECOVERING) {
            // 排空/恢复窗内的写入 = 已收场对话的残留补发：拒绝落口（响应侧由排空丢弃，
            // 写入侧由此拒绝——两半合起来才是完整的残留防线）
            req.ack.completeExceptionally(new SerialWriteException(
                    "串口正在排空/恢复，残留写拒绝: port=" + portName + ", state=" + state));
            return;
        }
        try {
            performWrite(req.bytes);
            req.ack.complete(Boolean.TRUE);
        } catch (SerialWriteException e) {
            req.ack.completeExceptionally(e);
        } catch (Throwable t) {
            req.ack.completeExceptionally(new SerialWriteException("串口写入失败: port=" + portName, t));
        }
    }

    private void performWrite(byte[] bytes) {
        if (!port.isFdOpen()) {
            log.warn("[WRITE-REOPEN] port={}, 端口未开, 写前自动重开", portName);
            port.openPortNow("[auto-reopen]");
            if (!port.isFdOpen()) {
                log.warn("[WRITE-REOPEN-FAIL] port={}, 自动重开失败, 写路径抛 SerialWriteException", portName);
                throw new SerialWriteException("串口重开失败(端口不可用): port=" + portName);
            }
        }
        if (port.isTestMode()) {
            // 测试模式 mock 口：写前排空内核层残留字节（生产路径清应用层缓冲）
            port.transport().drainRx();
        } else {
            port.clearReceiveBuffer();
        }
        int written = port.transport().write(bytes, bytes.length);
        Conversation conv = current;
        if (written >= 0) {
            if (state == PortState.AWAIT_WRITE) {
                state = PortState.AWAIT_RESPONSE;
            }
            port.traceTx(bytes, conv != null ? conv.owner : null);
            log.debug("[CONV] port={}, seq={}, 写出 {} B", portName, conv != null ? conv.seq : -1, bytes.length);
        } else {
            port.traceTxError();
            log.warn("[WRITE-FAIL] port={}, writeBytes 返 {} (非阻塞反压, 端口已被关闭), 写路径抛 SerialWriteException",
                    portName, written);
            throw new SerialWriteException("串口写入失败(writeBytes 返 " + written
                    + ", 端口已被反压关闭): port=" + portName);
        }
    }

    // ==================== 关闭语义（§3.4） ====================

    private void handleRetire() {
        log.warn("[CONV] port={}, 退役（最后一份 source 已注销）：终态 CLOSED，未开始对话全部取消，"
                + "在飞对话跑到自然结束", portName);
        state = PortState.CLOSED;
        cancelAllQueued("port retired");
        port.stopPolling();
        port.closeFd("[port-retired]");
    }

    private void cancelQueuedOfSession(SerialSource source) {
        List<Conversation> cancelled = new ArrayList<>();
        synchronized (admissionLock) {
            cancelQueuedIn(highQueue, source, cancelled);
            cancelQueuedIn(normalQueue, source, cancelled);
        }
        for (Conversation conv : cancelled) {
            log.debug("[CONV] port={}, seq={}, session 已关闭，未开始对话取消", portName, conv.seq);
        }
    }

    private void cancelQueuedIn(ArrayDeque<Conversation> queue, SerialSource source,
            java.util.List<Conversation> out) {
        Iterator<Conversation> it = queue.iterator();
        while (it.hasNext()) {
            Conversation conv = it.next();
            if (conv.session == source) {
                it.remove();
                finishCancelled(conv, "session closed");
                out.add(conv);
            }
        }
    }

    private void cancelAllQueued(String reason) {
        synchronized (admissionLock) {
            Conversation conv;
            while ((conv = highQueue.pollFirst()) != null) {
                finishCancelled(conv, reason);
            }
            while ((conv = normalQueue.pollFirst()) != null) {
                finishCancelled(conv, reason);
            }
        }
    }

    /** 未开始对话的取消收尾（closed 语义异常完成其 future，§3.4）。 */
    private void finishCancelled(Conversation conv, String reason) {
        cancel(conv.waitTimer);
        conv.state = ConvState.DONE;
        conv.result.completeExceptionally(new SerialSourceClosedException("conversation(" + reason + ")",
                portName, conv.session != null ? conv.session.getIdentity() : "unknown"));
        if (!conv.granted.isDone()) {
            conv.granted.cancel(false);
        }
    }

    // ==================== 恢复与强拆 ====================

    /**
     * 外部恢复（recoverWedgedPort）：空口立即 close+reopen；在飞/排空中挂起，收场后执行
     * （恢复不中断进行中对话——口执行线程单点串行的自然推论）。
     */
    private void handleRecover(String reason) {
        if (state == PortState.CLOSED) {
            // 已退役：迟到自愈的重开在入口被拒（[OPEN-REJECTED]，孤儿 fd 防线单点）
            port.openPortNow(reason);
            return;
        }
        if (current != null || state == PortState.DRAINING || state == PortState.RECOVERING) {
            if (pendingRecoverReason == null) {
                pendingRecoverReason = reason;
                log.warn("[WEDGE-RECOVERY] port={}, 原因: {}, 对话在飞，恢复挂起至收场后执行", portName, reason);
            }
            return;
        }
        performRecoveryNow(reason);
    }

    private void performRecoveryNow(String reason) {
        log.error("[WEDGE-RECOVERY] port={}, 原因: {}, 强制 close+reopen 拆除挂死 IO", portName, reason);
        state = PortState.RECOVERING;
        port.stopPolling();
        port.closeFd(reason);
        try {
            port.openPortNow(reason);
        } catch (RuntimeException openFailed) {
            // 重开失败不得滞留状态机：落错误后回到 IDLE（写路径自动重开兜底）
            log.error("[WEDGE-RECOVERY] port={}, 重开失败（{}），端口保持关闭，写路径自动重开兜底",
                    portName, openFailed.getMessage());
        }
        state = PortState.IDLE;
    }

    private void runPendingRecoverIfAny() {
        String reason = pendingRecoverReason;
        if (reason != null) {
            pendingRecoverReason = null;
            performRecoveryNow(reason);
        }
    }

    /** 强拆当前在飞对话并排空（markTransactionAborted：超时方主动放弃，残留防线收口）。 */
    private void handleAbort(String reason) {
        Conversation conv = current;
        if (conv == null || conv.state != ConvState.RUNNING) {
            log.info("[CONV] port={}, 收到强拆请求（{}）但无在飞对话，无事可做", portName, reason);
            return;
        }
        log.warn("[CONV] port={}, seq={}, 对话被强拆（{}），异常收场并排空", portName, conv.seq, reason);
        if (!conv.result.isDone()) {
            conv.result.completeExceptionally(new TimeoutException("Transaction aborted: " + reason));
        }
        finishConversation(conv, true);
    }

    // ==================== 缓冲回放 ====================

    /**
     * 监听器注册时的缓冲回放（addDataListener 既有语义）：缓冲归口执行线程私有后，
     * 回放=口执行线程快照读并投递该监听器。写前清缓冲 + 异常结束排空使回放天然不跨对话
     * （2×超时回放窗退役的等价替代：串行使「缓冲内容只属于当前对话」成为结构事实）。
     * 回放回调沿用监听器轻量契约（组帧/转交，禁阻塞）。
     */
    private void handleReplay(SerialDataListener listener) {
        byte[] buffered = port.readAndClearReceiveBuffer();
        if (buffered != null && buffered.length > 0) {
            listener.onDataReceived(buffered, buffered.length);
        }
    }

    // ==================== 队列操作与记账 ====================

    /** 取排序队头（同优先级先来后到，高优先级整队在前；admissionLock 内）。 */
    private Conversation pollHead() {
        synchronized (admissionLock) {
            Conversation head = highQueue.pollFirst();
            return head != null ? head : normalQueue.pollFirst();
        }
    }

    /** 移除仍在队的指定对话（等待超时方离队；返回 false=已不在队）。 */
    private boolean removeQueued(Conversation conv) {
        synchronized (admissionLock) {
            return highQueue.remove(conv) || normalQueue.remove(conv);
        }
    }

    private boolean queuesEmpty() {
        synchronized (admissionLock) {
            return highQueue.isEmpty() && normalQueue.isEmpty();
        }
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    /** 真弃轮记账（E2/R3 契约保持：放弃必须可计数 + 限频告警，禁静默）。 */
    private void recordPollingSkip(String detail) {
        long skips = lockBusySkipCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastBusySkipLogAt >= BUSY_SKIP_LOG_INTERVAL_MS) {
            lastBusySkipLogAt = now;
            log.warn("Polling conversation skipped ({}): port={}, total skips={}, state={}, queued={}",
                    detail, portName, skips, state, queuedCount());
        }
    }
}
