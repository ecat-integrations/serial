package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.SerialPortExecutor.Priority;

/**
 * The {@code SerialTransactionStrategy} class provides the two transaction entries over the
 * per-port serial executor (serial IO 最小模型 §3.5/§3.6)：把设备事务体作为一场对话提交到
 * 端口的有界优先级等待队列——调用线程<b>入队即返回 future</b>，不阻塞；事务体轮到时在
 * IO 工作线程启动（口执行线程永不跑设备代码），返回 future 的正常完成点也在 IO 工作线程。
 *
 * <p>Usage example:
 * <pre>
 * {@code
 * SerialSource source = ...;
 * CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
 *     source,
 *     src -> CompletableFuture.supplyAsync(() -> {
 *         // Perform operations with the source
 *         return true;
 *     })
 * );
 * }
 * </pre>
 *
 * <p><b>入口选择规则（SDK 调度纪律，两个入口非冗余）</b>：集成设备代码不应直接调用
 * {@code SerialSource#acquire/acquirePollingBounded}，按事务的时效语义选入口——
 * <ul>
 *   <li>命令/写事务 → {@link #executeWithLambda(SerialSource, Function)}：高优先级入队，
 *       等锁预算内未授予则 future 异常完成——命令的时效语义是「最终要执行」，有限等待
 *       是正确的；队满则<b>同步抛出</b> {@link PortQueueFullException}（背压显式可见）；</li>
 *   <li>周期轮询 → {@link #executePolling(SerialSource, Function)}：普通优先级入队——与
 *       写命令同队同公平；排队等待预算（周期÷4 clamp[200,500]ms）耗尽或队满时 future 以
 *       {@link LockBusySkippedException}（含子类 {@link PortQueueFullException}）异常完成
 *       ——周期任务消费方把该异常识别为「本轮跳过」（SerialPolling 内化，真弃轮在源侧
 *       记账 {@code getLockBusySkipCount()}）。</li>
 * </ul>
 * round/事务临界体内需要追加直发命令时，使用 SDK 注入的 source 直接 send（此时本对话
 * 在飞，写请求在口执行线程与其余 IO 串行），勿再经本类入口二次入队——同线程嵌套提交会
 * 排队到自身之后，等待自身完成即自死锁。
 *
 * <p><b>超时语义</b>：每场对话一个 deadline（自授予起算，{@code transactionTimeoutMs}），
 * 由执行者看门狗执法——到点 future 必然异常完成（{@link java.util.concurrent.TimeoutException}），
 * fd 被看门狗跨线程强拆、重开并排空后开下一场。调用方无需（也不再）自行施加超时包装。
 *
 * <p><b>使用契约（防调度池饿死，B5 纪律保持）</b>：本方法入队即返回，调用线程零 park；
 * 返回 future 必须异步消费（fire-and-forget / whenComplete），禁止周期调度任务内
 * {@code .get()}/{@code .join()} 阻塞。
 *
 * @author coffee
 *
 * @see SerialSource
 * @see SerialPortExecutor
 * @see CompletableFuture
 */
public class SerialTransactionStrategy {

    private static final Log log = LogFactory.getLogger(SerialTransactionStrategy.class);

    /**
     * 事务级硬超时倍数：相对设备配置的串口读超时（单次读）的放大系数。
     * 一次事务通常含多次 send-read（如 sailhero CO = getRealData().thenCompose(getStatusData) = 2 次读），
     * 故按倍数放大以覆盖整事务 + 安全余量；长事务设备（标定/多步流程）应改用
     * {@link #executeWithLambda(SerialSource, Function, long)} 显式传入对应其 .get(N,SECONDS) 的值。
     */
    static final int TRANSACTION_TIMEOUT_FACTOR = 10;

    /**
     * 3 参 {@link #executePolling(SerialSource, Function, long)} 的默认排队等待预算（毫秒）：
     * 直调入口的消费方无周期上下文，取 SDK clamp 上界
     * （同 {@code SerialPolling#lockWaitBudgetMs()} 上限）。
     */
    private static final long DEFAULT_LOCK_BUDGET_MS = 500L;

    /**
     * 执行一次串口事务（默认事务级硬超时由设备配置的串口超时派生）。
     *
     * <p>所有既有调用点（约 50 处）零改动即获得事务级硬超时保护。
     *
     * <p>返回 future 的<b>使用契约</b>（禁止周期调度任务内 {@code .get()} 阻塞、防 B5 复发）
     * 见类 Javadoc。
     *
     * @see #resolveDefaultTransactionTimeoutMs(SerialSource)
     */
    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source, Function<SerialSource, CompletableFuture<Boolean>> lambda) {
        return submit(source, lambda,
                SerialSourcePort.DEFAULT_ACQUIRE_WAIT_SECONDS * 1000L,
                resolveDefaultTransactionTimeoutMs(source));
    }

    /**
     * 解析默认事务级硬超时（毫秒）：沿用设备配置的串口超时
     * （{@code source.getTimeout() > 0 ? source.getTimeout() : Const.READ_TIMEOUT_MS}），
     * 再按 {@link #TRANSACTION_TIMEOUT_FACTOR} 放大以覆盖含多次读的整事务。
     *
     * <p>public 供跨集成消费方派生「等待串口侧资源」的有界上限（103000：teledyne 的事务 permit
     * 等待取本值——等满一个事务级硬超时仍未取得，即上一事务已超出其硬超时保证，等待方按失败
     * 处理而非无限钉死线程）。
     *
     * @return 事务级硬超时（毫秒）；source 为 null 或其串口超时无效时回退 Const.READ_TIMEOUT_MS × 倍数
     */
    public static long resolveDefaultTransactionTimeoutMs(SerialSource source) {
        int deviceTimeout = (source != null && source.getTimeout() > 0)
                ? source.getTimeout() : Const.READ_TIMEOUT_MS;
        return (long) deviceTimeout * TRANSACTION_TIMEOUT_FACTOR;
    }

    /**
     * 执行一次串口事务（显式事务级硬超时档）。
     *
     * <p>不同设备可按各自事务长度（多步/标定流程）显式传入 {@code transactionTimeoutMs}，
     * 取值应覆盖一次完整事务的耗时（含多次 send-read）及安全余量；默认（2 参重载）由
     * {@link #resolveDefaultTransactionTimeoutMs(SerialSource)} 从设备配置超时派生。
     *
     * @param source               串口资源
     * @param lambda               事务体（轮到时在 IO 工作线程执行）；返回的 future 完成即视为对话结束
     * @param transactionTimeoutMs 事务级硬超时（毫秒，自授予起算）；到点由执行者看门狗强制异常完成
     * @return 事务结果 future（必然 complete）；队满时<b>同步抛出</b> {@link PortQueueFullException}
     */
    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        return submit(source, lambda,
                SerialSourcePort.DEFAULT_ACQUIRE_WAIT_SECONDS * 1000L, transactionTimeoutMs);
    }

    /**
     * {@link #executeWithLambda(SerialSource, Function, long)} 的等锁时长可配档：
     * 队列等待预算显式传入（默认档取端口 5s），事务级硬超时仍取设备配置派生值。
     *
     * @param lockWait     队列等待预算
     * @param lockWaitUnit 预算时间单位
     */
    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda,
            long lockWait, TimeUnit lockWaitUnit) {
        return submit(source, lambda, lockWaitUnit.toMillis(lockWait),
                resolveDefaultTransactionTimeoutMs(source));
    }

    /** 写/命令事务提交本体（高优先级对话；队满同步抛出，视图关闭落 closed 异常 future）。 */
    private static CompletableFuture<Boolean> submit(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long waitMs, long transactionTimeoutMs) {
        if (transactionTimeoutMs <= 0) {
            // 严格模式：非正的事务超时是调用方编程错误（默认重载由
            // resolveDefaultTransactionTimeoutMs 派生，恒为正），显式抛出不静默兜底
            throw new IllegalArgumentException(
                    "transactionTimeoutMs must be > 0, got: " + transactionTimeoutMs);
        }
        try {
            return source.submitTransaction(lambda, Priority.HIGH, waitMs, transactionTimeoutMs);
        } catch (PortQueueFullException full) {
            // §3.5：队满同步抛出——命令入口的背压显式可见，不伪装成 future 失败
            throw full;
        } catch (SerialSourceClosedException closed) {
            return closedFailure(source, "executeWithLambda");
        }
    }

    /**
     * 轮询事务入口（默认事务级硬超时，由设备配置的串口超时派生，见
     * {@link #resolveDefaultTransactionTimeoutMs(SerialSource)}）。
     */
    public static CompletableFuture<Boolean> executePolling(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda) {
        return executePolling(source, lambda, resolveDefaultTransactionTimeoutMs(source));
    }

    /**
     * 轮询事务入口（默认排队等待预算）：普通优先级对话入队——与写命令同队同公平；
     * 排队等待预算（{@link #DEFAULT_LOCK_BUDGET_MS}）耗尽或队满时 future 以
     * {@link LockBusySkippedException} 异常完成（真弃轮，源侧记账）。
     *
     * <p><b>边界</b>：只供周期轮询任务体使用；写命令（MANUAL_COMMAND 经闸）与需要有限等待
     * 语义的调用方继续走 {@code executeWithLambda}。本入口把整个 lambda 包进单场对话，
     * 只适用于「一次轮询、过程中无等待、无分段交互」的单段轮体——轮体内含 delay/命令间
     * 留隙等待的设备必须改走 {@code SerialPolling#roundChain()}（每段独立对话、留隙在
     * 锁外），否则整轮持口=各笔 IO+等待总和，挤爆写命令的有界等待预算。
     *
     * @param source               串口资源
     * @param lambda               事务体（轮到时在 IO 工作线程执行）
     * @param transactionTimeoutMs 事务级硬超时（毫秒），须 &gt; 0
     * @return 事务结果 future；真弃轮时为 {@link LockBusySkippedException} 异常 future
     */
    public static CompletableFuture<Boolean> executePolling(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        return executePolling(source, DEFAULT_LOCK_BUDGET_MS, lambda, transactionTimeoutMs);
    }

    /**
     * 轮询事务入口的预算显式档（SDK 周期链接入面）：排队等待预算由调用方按轮询周期 clamp
     * （{@code SerialPolling#lockWaitBudgetMs()}：period÷4 clamp [200,500]ms）传入——周期短
     * 的链预算短（弃轮早、自愈快），周期长的链预算长（共享口公平窗口大）。
     *
     * @param source               串口资源
     * @param lockBudgetMs         排队等待预算（毫秒，须 &gt; 0；SDK 侧按轮询周期 clamp）
     * @param lambda               事务体（轮到时在 IO 工作线程执行）
     * @param transactionTimeoutMs 事务级硬超时（毫秒），须 &gt; 0
     * @return 事务结果 future；真弃轮时为 {@link LockBusySkippedException} 异常 future
     *         （含队满的 {@link PortQueueFullException}——周期链按既有 isLockBusySkip 内化）
     */
    public static CompletableFuture<Boolean> executePolling(SerialSource source, long lockBudgetMs,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        if (transactionTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "transactionTimeoutMs must be > 0, got: " + transactionTimeoutMs);
        }
        try {
            return source.submitTransaction(lambda, Priority.NORMAL, lockBudgetMs, transactionTimeoutMs);
        } catch (PortQueueFullException full) {
            // 队满=本轮跳过信号（记账已在提交点完成）：专用异常异常完成，
            // 落既有 isLockBusySkip 内化路径（SerialPolling settleRound 跳过本轮不回调）
            log.debug("Polling transaction skipped: port conversation queue full: {}", full.getMessage());
            CompletableFuture<Boolean> skipped = new CompletableFuture<>();
            skipped.completeExceptionally(full);
            return skipped;
        } catch (SerialSourceClosedException closed) {
            return closedFailure(source, "executePolling");
        }
    }

    private static CompletableFuture<Boolean> closedFailure(SerialSource source, String operation) {
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SerialSourceClosedException(
                operation, source.getPortName(), source.getIdentity()));
        return failed;
    }
}
