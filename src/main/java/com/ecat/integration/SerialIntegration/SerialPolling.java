package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Task.runner.PeriodicChain;
import com.ecat.core.Task.runner.PeriodicRunner;
import com.ecat.core.Task.runner.RoundSchedule;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * 主动轮询模式 SDK（L2 传输层，17 号 v2.1 §2.1——serial 域轮询的统一入口）。
 *
 * <p>应用场景：串口设备的周期采集（约 40 仓共 50 处 {@code scheduleWithFixedDelay +
 * executePolling + thenAccept/exceptionally} 样板，调研 01 §2.2）。设备仓的执行词汇
 * 收敛为「round 函数（读什么）」——调度注册 / 源锁（tryAcquire+锁忙跳过）/ 事务级硬超时 /
 * 异常韧性（永不注销）/ 统一日志全部内置：
 *
 * <pre>{@code
 * // 设备仓迁移终态（调研 01 §5.3 例 1，zhengxin 14 行 → 3 行；18 号设计 §3.3）
 * this.polling = SerialPolling.on(this, serialSource)
 *         .round(source -> getData().thenCompose(v -> getRATEDData()))
 *         .every(5, TimeUnit.SECONDS)
 *         .start();
 * }</pre>
 *
 * <p><b>调度与生命周期来源（29 号 v2 S1：serial 域自持定时，脱离 core 调度引擎）</b>：
 * 工厂 {@code on(RemovalHost, SerialSource)}——定时由域自持 {@link SerialSdkTimers}
 * 承载（daemon 池 ecat-serial-sched-N，周期链骨架/MDC/重排原语消费 core 库
 * PeriodicRunner/PeriodicChain，网格策略域侧 {@link SerialPollSchedule}），轮询句柄在
 * {@link #start()} 内部经 {@code host.onRemove(handle::cancel)} 注册到宿主移除生命周期
 * （DeviceBase LIFO sweep 统一执行）。设备作者零调度知识、零生命周期知识——忘了绑定的
 * 错误在签名层面不可能（host 必填，无双 API）。
 *
 * <p><b>round 契约</b>（17 号 v2.1 定稿）：一轮读什么，返回 CF。泛型接收端放宽
 * {@code ? extends CompletableFuture<?>}——容纳 {@code CF<Void>}（thermofisher 分块形态）
 * 与 {@code CF<Boolean>}（现状 50+ 处主流形态）：
 * <ul>
 *   <li>结果为 {@link Boolean#FALSE}：本轮业务失败——SDK 统一 warn（替代各仓 thenAccept
 *       样板），不视为错误；</li>
 *   <li>结果为 TRUE / null / 非 Boolean（如 Void）：本轮成功；</li>
 *   <li>异常完成：传输/设备错误——SDK 统一 error + {@code onRound(null, ex)}，轮询不注销
 *       （调度三原则「永不注销」，16 号 §4.4 异常不注销——111200 的修复载体）。</li>
 * </ul>
 *
 * <p><b>锁忙内化</b>：{@link LockBusySkippedException}（executePolling 的 tryAcquire
 * 锁忙信号）在 SDK 内部消化——不外泄到 {@code onRound} 回调、不打错误日志，本轮 CF 以
 * 正常完成结算（周期网格不变，下周期再试）。消费方零感知，调研 01 §4.2 C1 的 35 处
 * {@code isLockBusySkip} exceptionally 样板随之消亡。
 *
 * <p><b>生命周期</b>：{@link #start()} 内部是 serial 仓唯一周期注册点（L2 收口处：
 * 域定时器上的完成点重排周期链）；轮询句柄经 {@code host.onRemove(handle::cancel)}
 * 绑定宿主移除生命周期（cancel 纯标记天然满足 RemovalHost 非阻塞契约），设备 stop 的
 * 托管 sweep 与 {@link PollingHandle#cancel()} 幂等并存。多段命令链（cecep/saimosen
 * 形态）是 round 内一等公民，命令间延迟经 {@link #delay()}（配合
 * {@link #interCommandDelayMs(long)}，收编 9 文件本地 {@code delay()} 样板）或公有糖
 * {@link #delay(long, TimeUnit)}（一次性延迟，B 族收编入口；两者都走域定时器）。
 *
 * @author coffee
 */
public final class SerialPolling {

    private static final Log log = LogFactory.getLogger(SerialPolling.class);

    private final SerialSource source;
    /** 宿主移除生命周期（start() 尾部绑定 handle::cancel；18 号设计 §3.3）。 */
    private final RemovalHost host;
    /** 构造期一次性解析（source.getPortName 非稳定 API，日志热路径不应反复求值/抛错）。 */
    private final String portName;

    private Function<SerialSource, ? extends CompletableFuture<?>> round;
    private long periodMs;
    /** 首轮延迟（毫秒）；0 = 立即首轮（默认，与存量设备仓行为一致）。 */
    private long initialDelayMs;
    /** true = 名义网格 FixedRate 语义（到拍上轮未完成的拍跳过）；默认 fixedDelay 完成点语义。 */
    private boolean fixedRate;
    private Long interCommandDelayMs;
    private BiConsumer<Boolean, Throwable> roundCallback;
    /** 纳米钟（网格锚点用；默认系统单调钟，测试注入假钟确定性驱动，非消费方 API）。 */
    private LongSupplier nanoClock = System::nanoTime;
    private PollingHandle handle;
    /**
     * 断连态去重标志（comm 熔断退役后的补偿观测）：true = 当前处于连续失败期。
     * 失败轮 = 传输错误（非锁忙）或业务 false；锁忙轮是内部跳过信号，不改本态。
     * 轮次在本 SDK 内串行（fixedDelay 完成点重排 / fixedRate 到拍跳拍），volatile 足够。
     */
    private volatile boolean linkDown;

    private SerialPolling(RemovalHost host, SerialSource source) {
        this.host = host;
        this.source = source;
        this.portName = source.getPortName();
    }

    /**
     * 创建轮询构建器（18 号设计 §3.3 唯一工厂形态，无双 API）。
     *
     * @param host   移除动作宿主（设备侧传 {@code this}——轮询句柄的 cancel 自动注册到设备
     *               移除生命周期；测试用假宿主 {@code action -> {}} 或收集断言型）
     * @param source 串口资源（round 的事务载体）
     */
    public static SerialPolling on(RemovalHost host, SerialSource source) {
        if (host == null) {
            throw new IllegalArgumentException("host 不能为 null（设备侧传 this；测试用假宿主）");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 不能为 null");
        }
        return new SerialPolling(host, source);
    }

    /**
     * round 契约（17 号 v2.1 定稿）：一轮读什么。SDK 把整个 round 包进一次
     * {@code executePolling} 事务（tryAcquire 非阻塞取锁 + 事务级硬超时 + release 保证），
     * 多段命令链（{@code thenCompose} 串联多笔 send-read）在单事务内天然串行——
     * cecep 式「同周期两笔独立事务互踩锁」形态（调研 07 §17）合并为单 round 即消。
     *
     * @param round 一轮事务体（持锁期间执行）；返回 CF 见类 Javadoc 的结果/异常契约
     */
    public SerialPolling round(Function<SerialSource, ? extends CompletableFuture<?>> round) {
        if (round == null) {
            throw new IllegalArgumentException("round 不能为 null");
        }
        this.round = round;
        return this;
    }

    /**
     * 轮询周期（fixedDelay 语义，16 号 §4.4）：本轮 CF 完成点（正常/异常）+ period
     * = 下轮发起点——事务在飞不重入、不提前发射；锁忙跳过轮瞬时结算，网格不变。
     *
     * @param period 周期长度，须 &gt; 0
     * @param unit   时间单位
     */
    public SerialPolling every(long period, TimeUnit unit) {
        if (period <= 0 || unit == null) {
            throw new IllegalArgumentException("period 须 > 0 且 unit 非 null, period=" + period);
        }
        long ms = unit.toMillis(period);
        if (ms <= 0) {
            throw new IllegalArgumentException("period 换算为毫秒后须 > 0, period=" + period + " " + unit);
        }
        this.periodMs = ms;
        return this;
    }

    /**
     * 首轮延迟（默认 0 = 立即首轮）：设备上电/串口就绪窗场景（santak 5s、teledyne-api 1s、
     * tjtongyangkeji 1s/2s——原「schedule 一次性任务延后整个轮询注册」workload 的原生承载，
     * 与 ModbusPolling#initialDelay 同一链式选项）。任务在 {@link #start()} 即经引擎注册
     * （宿主移除动作同点注册）：延迟窗内 stop 的移除 sweep 与 {@link PollingHandle#cancel()} 同样
     * 生效，无「迟到启动」竞态；到点前 round 不执行、onRound 不回调。
     *
     * @param delay 首轮延迟，须 &gt;= 0（0 = 立即首轮，与不调用本方法行为一致）
     * @param unit  时间单位
     */
    public SerialPolling initialDelay(long delay, TimeUnit unit) {
        if (delay < 0 || unit == null) {
            throw new IllegalArgumentException("initialDelay 须 >= 0 且 unit 非 null, delay=" + delay);
        }
        long ms = unit.toMillis(delay);
        if (ms < 0) {
            throw new IllegalArgumentException("initialDelay 换算为毫秒后须 >= 0（溢出）, delay=" + delay + " " + unit);
        }
        this.initialDelayMs = ms;
        return this;
    }

    /**
     * 切换为固定速率语义（名义网格发射、到拍时上轮 CF 未完成的拍跳过——与 ModbusPolling
     * 的 fixedRate() 同语义；网格推进/跨拍跳过/过期即弃由域侧 {@link SerialPollSchedule}
     * 承载，29 号 v2 S1 起不再依赖引擎 scheduleAtFixedRate 原语）。serial 域存量设备仓
     * 全为 fixedDelay；semeatech/gassensor 两仓既有 scheduleAtFixedRate 节律迁 SDK 时以
     * 本开关保持等价。
     */
    public SerialPolling fixedRate() {
        this.fixedRate = true;
        return this;
    }

    /**
     * round 内命令间延迟配置（配合 {@link #delay()} 使用）：收编 sailhero/saimosen
     * 共 9 文件的本地 {@code delay()} 助手样板（轮内多命令间留隙以适应设备性能）。
     *
     * @param ms 命令间延迟（毫秒）；0 = 立即完成（显式声明无延迟）
     */
    public SerialPolling interCommandDelayMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("interCommandDelayMs 须 >= 0, got: " + ms);
        }
        this.interCommandDelayMs = ms;
        return this;
    }

    /**
     * 轮次完成回调（替代各仓 thenAccept/exceptionally 样板）。正常完成收
     * {@code (roundResult, null)}（roundResult 语义见类 Javadoc：Boolean 结果或 null）；
     * 传输错误收 {@code (null, ex)}。<b>锁忙跳过不回调</b>（round 未执行，
     * 无结果可报）。
     *
     * <p>回调线程 = 完成 future 的线程（IO/超时调度线程，16 号 §4.4 完成回调线程纪律）：
     * 须保持轻量（属性发布/记账），禁止阻塞等待与再发起传输事务。
     *
     * @param callback 轮次完成回调；null = 不注册
     */
    public SerialPolling onRound(BiConsumer<Boolean, Throwable> callback) {
        this.roundCallback = callback;
        return this;
    }

    /**
     * 纳米钟注入（默认系统单调钟；单测确定性驱动网格/过期判定用，非消费方 API）。
     */
    SerialPolling withNanoClock(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("nanoClock 不能为 null");
        }
        this.nanoClock = clock;
        return this;
    }

    /**
     * round 内命令间延迟（须先经 {@link #interCommandDelayMs(long)} 配置）：经域定时器
     * {@link SerialSdkTimers} 的 MDC 包装单发完成 CF，round 链内以
     * {@code thenCompose(v -> polling.delay())} 形态插入命令之间。设备侧两步构建
     * （先建 builder 再挂 round/start）保证 round 体可无竞态引用本方法（参见 saimosen
     * SMS8600V2Device 迁移形态）。
     *
     * @return 到点完成的 CF（不失败；调度提交失败抛 RejectedExecutionException——
     *         域定时器已停机的显式信号）。延迟属在飞轮次的一部分，不打断在飞轮次的
     *         cancel 语义（RemovalHost 非阻塞契约）对其不额外注册移除动作。
     */
    public CompletableFuture<Void> delay() {
        Long ms = interCommandDelayMs;
        if (ms == null) {
            throw new IllegalStateException(
                    "delay() 须与 .interCommandDelayMs(long) 成对配置后使用");
        }
        return delay(ms, TimeUnit.MILLISECONDS);
    }

    /**
     * 一次性延迟公有糖（29 号 v2 S2 的 B 族收编入口：设备仓散落的「schedule 一次性任务
     * 延后做事」workload 迁 SDK 时的统一词汇）：经域定时器单发到点完成，延迟值显式传入
     * （无需 interCommandDelayMs 成对配置）。与 {@link #delay()} 同走
     * {@link SerialSdkTimers}（MDC 提交时捕获、到拍恢复）。
     *
     * @param delay 延迟时长，须 &gt;= 0（0=立即完成，显式声明无延迟）
     * @param unit  时间单位
     * @return 到点完成的 CF（不失败；域定时器停机时提交抛 RejectedExecutionException）
     */
    public CompletableFuture<Void> delay(long delay, TimeUnit unit) {
        if (delay < 0 || unit == null) {
            throw new IllegalArgumentException("delay 须 >= 0 且 unit 非 null, delay=" + delay);
        }
        long ms = unit.toMillis(delay);
        if (ms < 0) {
            throw new IllegalArgumentException("delay 换算为毫秒后须 >= 0（溢出）, delay=" + delay + " " + unit);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        SerialSdkTimers.fireAfter(() -> future.complete(null), ms);
        return future;
    }

    /**
     * 启动轮询：内部把 round 包成轮体 Supplier 交给 {@link PeriodicRunner#periodic}（core
     * 库完成点重排周期链——serial 仓唯一周期注册点，L2 收口），执行器为域自持
     * {@link SerialSdkTimers}（29 号 v2 S1：引擎/SdkSchedulerResolver 依赖退役）。
     * fixedDelay（默认）与 fixedRate 的差异全部在域侧网格策略 {@link SerialPollSchedule}
     * （完成点+period 重排 vs 名义网格到拍跳拍；含过期即弃 skips=lag/period+1）。
     * 注册完成后 {@code host.onRemove(handle::cancel)} 把轮询句柄绑定到宿主移除生命周期
     * （cancel 纯标记满足 RemovalHost 非阻塞契约）。首轮延迟由
     * {@link #initialDelay(long, TimeUnit)} 配置（默认 0 = 立即首轮，与全部存量设备仓一致），
     * begin 段同步异常包 failedFuture 结算（16 号 §4.4）。
     *
     * @return 轮询句柄（cancel/isRunning；宿主移除 sweep 与显式 cancel 幂等并存）
     * @throws IllegalStateException round/every 未配置、或本实例已 start（一构建一启动）
     */
    public PollingHandle start() {
        if (round == null) {
            throw new IllegalStateException("start() 前必须配置 round(Function)");
        }
        if (periodMs <= 0) {
            throw new IllegalStateException("start() 前必须配置 every(period, unit)");
        }
        if (handle != null) {
            throw new IllegalStateException("本 SerialPolling 已 start（一构建一启动；重启场景新建构建器）");
        }
        // 事务级硬超时统一由设备配置派生（串口读超时 × 10；SDK 级覆盖词汇已按剃刀删除——零消费）
        long transactionTimeoutMs = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(source);
        RoundSchedule schedule = fixedRate
                ? SerialPollSchedule.fixedRate(periodMs, initialDelayMs, nanoClock, portName)
                : SerialPollSchedule.fixedDelay(periodMs, initialDelayMs, nanoClock, portName);
        PeriodicRunner runner = SerialSdkTimers.runner();
        PeriodicChain chain = runner.periodic(portName, () -> beginRound(transactionTimeoutMs), schedule);
        this.handle = new ManagedHandle(chain);
        // 结构化生命周期（18 号 §3.3）：销毁动作注册到宿主——L3 作者不接触生命周期概念
        host.onRemove(handle::cancel);
        chain.start();
        return this.handle;
    }

    /** 单轮发起：executePolling 事务 → 结算（日志/onRound/锁忙内化）。 */
    private CompletableFuture<?> beginRound(long transactionTimeoutMs) {
        CompletableFuture<Boolean> transaction;
        try {
            transaction = SerialTransactionStrategy.executePolling(
                    source, asBooleanRound(round), transactionTimeoutMs);
        } catch (RuntimeException e) {
            // 发起段同步异常（tryAcquire 内部错误等）：包 failedFuture 统一处理——
            // 引擎按异常完成记账、周期不注销（16 号 §4.4）；SDK 侧补齐日志与回调。
            log.error("[{}] polling round submission failed", portName, e);
            if (linkTimelineEnabled()) {
                markLinkDown("round submission failed: " + e.getMessage());
            }
            BiConsumer<Boolean, Throwable> cb = roundCallback;
            if (cb != null) {
                cb.accept(null, e);
            }
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        return transaction.handle(this::settleRound);
    }

    /**
     * 单轮结算（handle：观察 + 翻译）——锁忙分支正常完成（网格不变、不回调、不改断连态），
     * 传输错误保持异常完成（引擎失败记账可见，永不注销）。
     */
    private Object settleRound(Boolean result, Throwable ex) {
        if (ex != null) {
            if (LockBusySkippedException.isLockBusySkip(ex)) {
                // 锁忙是内部信号非错误：本轮跳过、下周期再试；源侧已有记账与限频日志
                log.debug("[{}] polling round skipped: port lock busy, retry next cycle", portName);
                return null;
            }
            log.error("[{}] polling round failed (transport/device error), polling continues", portName, ex);
            if (linkTimelineEnabled()) {
                markLinkDown("transport/device error: " + ex.getMessage());
            }
            BiConsumer<Boolean, Throwable> cb = roundCallback;
            if (cb != null) {
                cb.accept(null, ex);
            }
            throw rethrow(ex);
        }
        if (Boolean.FALSE.equals(result)) {
            log.warn("[{}] polling round business failure (round returned false)", portName);
            if (linkTimelineEnabled()) {
                markLinkDown("business failure (round returned false)");
            }
        } else {
            if (linkTimelineEnabled()) {
                markLinkRecovered();
            }
        }
        BiConsumer<Boolean, Throwable> cb = roundCallback;
        if (cb != null) {
            cb.accept(result, null);
        }
        return result;
    }

    /**
     * 断连时间线翻转门（幽灵 DOWN 行修复）：仅当轮询链仍在调度（或句柄尚未创建）时才允许
     * markLinkDown/markLinkRecovered 打断连时间线转移行。cancel 不打断在飞轮（PollingHandle
     * 契约）——已取消链的在飞轮随后被事务级硬超时迟到结算，若照常翻转时间线有两害：
     * 测试侧在本类全实例共享的 logger 上污染相邻用例的 DOWN/RECOVERED 计数（串扰实锤：
     * 相邻用例开局冒 DOWN(error: null)）；生产侧设备主动 stop 后数秒仍冒「link DOWN」
     * 幽灵断发行，误导运维定位。死链不产生时间线翻转——迟到结算的 per-round ERROR/WARN
     * 证据栈不受本门影响（失败轮本身照打，可 grep 定位根因）。
     *
     * @return true = 时间线翻转允许（链在调度 / 句柄未建=未启动即无取消态，按活链放行）
     */
    private boolean linkTimelineEnabled() {
        return handle == null || handle.isRunning();
    }

    /**
     * 断连态进入（去重）：连续失败期只在首败打一行 WARN——后续失败轮由既有 per-round
     * ERROR/WARN 承载（全栈可 grep 定位根因），转移行只负责运维一眼可见的断连时间线。
     */
    private void markLinkDown(String reason) {
        if (!linkDown) {
            linkDown = true;
            log.warn("[{}] polling link DOWN ({}), recovery will be logged", portName, reason);
        }
    }

    /** 断连态退出（去重）：断连后的首个成功轮打一行 INFO，构成 连续断连/恢复 时间线。 */
    private void markLinkRecovered() {
        if (linkDown) {
            linkDown = false;
            log.info("[{}] polling link RECOVERED (rounds succeeding again)", portName);
        }
    }

    /**
     * round 通配签名 → executePolling 的 CF<Boolean> 形参桥接。类型参数在
     * executePolling/executeHeld/withHardTimeout 全链只透传不检验（applyToEither +
     * identity，值原样流经），故本擦除桥接安全；SDK 结算侧只按 Boolean.TRUE/FALSE
     * 字面语义判读（非 Boolean 结果按成功处理，见类 Javadoc）。
     */
    @SuppressWarnings("unchecked")
    private static Function<SerialSource, CompletableFuture<Boolean>> asBooleanRound(
            Function<SerialSource, ? extends CompletableFuture<?>> round) {
        return src -> (CompletableFuture<Boolean>) round.apply(src);
    }

    /** 传输错误的异常完成保持：RuntimeException 原样重抛（保留类型），受检异常包 CompletionException。 */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new CompletionException(t);
    }

    /** {@link PollingHandle} 托管实现：委托周期链句柄（cancel 不打断在飞轮，见接口 Javadoc）。 */
    private static final class ManagedHandle implements PollingHandle {

        private final PeriodicChain chain;

        ManagedHandle(PeriodicChain chain) {
            this.chain = chain;
        }

        @Override
        public void cancel() {
            chain.cancel();
        }

        @Override
        public boolean isRunning() {
            return chain.isRunning();
        }
    }
}
