package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;


/**
 * The {@code SerialTransactionStrategy} class provides a mechanism to execute operations
 * in a serial manner using a locking strategy. It ensures that operations on a shared
 * resource are performed sequentially by acquiring and releasing a lock.
 *
 * <p>This class is designed to work with asynchronous operations using {@link CompletableFuture}.
 * It allows a lambda function to be executed with a {@link SerialSource}, ensuring that the
 * lock is properly released after the operation is completed, even in the case of exceptions.
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
 * <p>Key features:
 * <ul>
 *   <li>Ensures that the lock is acquired before executing the operation.</li>
 *   <li>Releases the lock after the operation is completed or if an exception occurs.</li>
 *   <li>Logs errors (key/duration/exception) for debugging purposes.</li>
 *   <li>Handles exceptions gracefully by returning a failed {@link CompletableFuture}.</li>
 * </ul>
 * 
 * @author coffee
 *
 * @see SerialSource
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
     * 执行一次串口事务（默认事务级硬超时由设备配置的串口超时派生）。
     *
     * <p>所有既有调用点（约 50 处）零改动即获得事务级硬超时保护。
     *
     * <p>返回 future 的<b>使用契约</b>（禁止周期调度任务内 {@code .get()} 阻塞、防 B5 LIVE 复发）
     * 见 {@link #executeWithLambda(SerialSource, Function, long)} 的 Javadoc。
     *
     * @see #resolveDefaultTransactionTimeoutMs(SerialSource)
     */
    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source, Function<SerialSource, CompletableFuture<Boolean>> lambda) {
        return executeWithLambda(source, lambda, resolveDefaultTransactionTimeoutMs(source));
    }

    /**
     * 解析默认事务级硬超时（毫秒）：参考现有模式
     * {@code DefaultResponseHandlerStrategy} / {@code ByteResponseHandlerStrategy}
     * （{@code source.getTimeout() > 0 ? source.getTimeout() : Const.READ_TIMEOUT_MS}）取设备配置的串口超时，
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
     * 执行一次串口事务：获取锁 → 执行 lambda → 释放锁，并施加事务级硬超时。
     *
     * <p>事务级硬超时保证 lambda 返回的 future 必然在 {@code transactionTimeoutMs} 内 complete
     * （成功或 {@link java.util.concurrent.TimeoutException}），从而 whenCompleteAsync 必触发 →
     * release 必执行，杜绝 B5（lambda future 永不 complete 导致端口锁永久泄漏）。
     *
     * <p><b>使用契约（重要 —— 防止 B5 在 LIVE 多设备工况复发）：</b>本方法返回的 future
     * <b>必须以异步方式消费</b>（fire-and-forget，结果/异常经 {@code whenComplete} 处理），
     * <b>禁止在调用线程上对其 {@code .get(N, TimeUnit)} / {@code .join()} 阻塞等待</b>，尤其禁止在
     * {@code scheduleWithFixedDelay} 等周期调度任务内阻塞。原因：
     * <ol>
     *   <li>周期调度任务共享 {@code ecat-scheduled} 线程池（线程数极少），阻塞式 {@code .get()}
     *       会长时间独占调度线程；多设备并发挂起时<b>饿死调度池</b>，全平台周期任务停摆
     *       （实证见 bug-record-20260619221536.md「第四次 LIVE 复现」节：2 调度线程被 5+ 设备的
     *       {@code .get(10s)} 占满 → 硬超时连发 12 次后失效 → 串口事务归零、457 次 lock-failure）。</li>
     *   <li>锁释放（{@code release}）绑定在该 future 的 {@code whenCompleteAsync} 上；而
     *       {@code .get(N)} 自身超时<b>不会 complete 底层 future</b>。一旦硬超时因调度饥饿未触发，
     *       future 永不 complete → {@code whenComplete} 永不执行 → 端口锁永久泄漏
     *       （此即 B5 的 LIVE 复现路径，非事务硬超时本身失效）。</li>
     * </ol>
     *
     * <p><b>正确范式</b>（19 号 v2 设备零调度后：轮询经 SerialPolling SDK + SdkTimers 域定时，
     * round 内只做事务编排——调度线程零阻塞）：
     * <pre>{@code
     * // 设备侧：round 函数返回事务 CF，周期链接入由 SerialPolling/SerialSdkTimers 收口
     * ScheduledFuture<?> scheduledFuture = serialPolling.start(device, this::pollRound);
     * }</pre>
     *
     * <p><b>例外</b>：请求驱动（非周期调度）的同步命令助手（如需同步返回字节数组的
     * {@code sendCommandSync}），在<b>不占用共享调度池线程</b>的调用线程上阻塞 {@code .get()} 可接受
     * ——但仍须保证 {@code .get()} 超时 <b>≥</b> {@code transactionTimeoutMs}，否则底层 future 未 complete
     * 同样泄漏。周期调度任务无此例外，一律异步消费。
     *
     * <p>不同设备可按各自事务长度（多步/标定流程）显式传入 {@code transactionTimeoutMs}，
     * 取值应覆盖一次完整事务的耗时（含多次 send-read）及安全余量；默认（2 参重载）由
     * {@link #resolveDefaultTransactionTimeoutMs(SerialSource)} 从设备配置超时派生。
     *
     * @param source               串口资源（提供 acquire/release 与设备配置超时）
     * @param lambda               在持锁期间执行的事务；返回的 future 完成即视为事务结束
     * @param transactionTimeoutMs 事务级硬超时（毫秒）；到点 future 未完成则强制异常完成以保证 release
     * @return 事务结果 future（必然 complete）；acquire 失败时为 IllegalStateException 异常 future
     */
    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        if (transactionTimeoutMs <= 0) {
            // 严格模式：非正的事务超时是调用方编程错误（应传入设备真实事务超时；
            // 默认 2 参重载由 resolveDefaultTransactionTimeoutMs 从设备配置派生，恒为正），
            // 此处明确抛出而非静默兜底；在 acquire 之前抛出，不持锁、不会泄漏。
            throw new IllegalArgumentException(
                    "transactionTimeoutMs must be > 0, got: " + transactionTimeoutMs);
        }
        String key = source.acquire();
        if (key != null) {
            return executeHeld(source, key, lambda, transactionTimeoutMs);
        } else {
            log.error("Failed to acquire lock");
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Failed to acquire lock"));
            return failedFuture;
        }
    }

    /**
     * 轮询事务入口（E2/R3 终态修复，方案 c：acquire 非阻塞化——调度三原则「过期即弃」）。
     *
     * <p>与 {@link #executeWithLambda(SerialSource, Function, long)} 的唯一差异在锁忙分支：
     * 经 {@link SerialSource#tryAcquire()} 非阻塞取锁——锁忙时<b>本周期立即放弃</b>（不 park
     * 等锁、不占等待队列、不消费 signal），返回以 {@link LockBusySkippedException} 异常完成
     * 的 future。周期任务的 whenComplete 消费方应把该异常识别为「本轮跳过」而非设备错误。
     * 放弃在源侧有记账（{@link SerialSource#getLockBusySkipCount()} + 限频 warn）。
     *
     * <p><b>完成语义（R4，16 号设计）</b>：本方法把事务 CF 经返回值流出——轮询调用方以
     * round 函数（readData 返回本方法的 CF）接入所属域 SDK 周期链（19 号 v2 设备零调度，
     * DeviceBase 托管视图已物理删除）：worker 只承担发起段，任务
     * 完成点=事务 CF 完成点（看门狗执法覆盖真实事务时长、熔断 outcome 基于事务结果、
     * fixedDelay 重排以事务真完成点起算）。曾在 R3 期 3 经引擎静态 attach 钩子接线（依赖
     * 倒置），R4 批 0 已删除——返回值即完成信号的唯一通道。非调度上下文（单测直调/旁池）
     * 消费方自行 whenComplete，fire-and-forget 契约不变。
     *
     * <p><b>边界</b>：只供周期轮询任务体使用；写命令（MANUAL_COMMAND 经闸）与需要有限等待
     * 语义的调用方继续走 {@code executeWithLambda}（闸内 IO 体对锁的等待保留）。
     *
     * <p>取锁成功后的事务体/硬超时/release/端口强拆链路与 {@code executeWithLambda} 完全共享
     * （{@link #executeHeld}），F-16 的收割/恢复机制不受影响。
     *
     * @param source               串口资源
     * @param lambda               在持锁期间执行的事务
     * @param transactionTimeoutMs 事务级硬超时（毫秒），须 &gt; 0
     * @return 事务结果 future；锁忙时为 {@link LockBusySkippedException} 异常 future（立即完成）
     */
    public static CompletableFuture<Boolean> executePolling(SerialSource source,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        if (transactionTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "transactionTimeoutMs must be > 0, got: " + transactionTimeoutMs);
        }
        String key = source.tryAcquire();
        if (key == null) {
            CompletableFuture<Boolean> skipped = new CompletableFuture<>();
            skipped.completeExceptionally(new LockBusySkippedException(
                    "Polling transaction skipped: port lock busy, will retry next cycle"));
            return skipped;
        }
        return executeHeld(source, key, lambda, transactionTimeoutMs);
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
     * 已持锁事务体的共享执行链（executeWithLambda 与 executePolling 的取锁后公共路径）：
     * 事务级硬超时 + whenComplete 内联 release + 超时强拆（B5 / Q-1/A2 修复语义原样保留）。
     */
    private static CompletableFuture<Boolean> executeHeld(SerialSource source, String key,
            Function<SerialSource, CompletableFuture<Boolean>> lambda, long transactionTimeoutMs) {
        try {
            long operationStartTime = System.currentTimeMillis();
            CompletableFuture<Boolean> operations = lambda.apply(source);
            // B5 修复：施加事务级硬超时，保证 future 必然 complete → whenComplete 必触发 → release 必执行
            CompletableFuture<Boolean> withTimeout = withHardTimeout(operations, transactionTimeoutMs);
            // Q-1/A2 修复：release 改为在「完成 future 的线程」上内联执行（whenComplete），
            // 不再 whenCompleteAsync 排队到 IO 车道——车道是 per-port 单线程，jSerialComm
            // writeBytes 挂死时 release 回调会被永久排在挂死写之后（即使硬超时已 complete
            // future），端口锁成幽灵锁、故障移除后不自愈（P0 验收 A2/A5 实证）。release 只
            // 短暂持有端口 ReentrantLock，在超时调度线程上执行是安全的。
            return withTimeout.whenComplete((res, ex) -> {
                long duration = System.currentTimeMillis() - operationStartTime;
                try {
                    if (ex != null) {
                        log.error("Transaction with key: {} failed after {} ms, exception: {}",
                                key, duration, ex.getMessage(), ex);
                    }
                } finally {
                    // applyToEither 依赖链可能把 TimeoutException 包成 CompletionException，解包判断
                    Throwable root = (ex instanceof java.util.concurrent.CompletionException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    if (root instanceof java.util.concurrent.TimeoutException) {
                        // 事务硬超时 = 端口 IO 挂死强证据：close+reopen 强拆挂死的本地阻塞写，
                        // 救活被钉死的车道线程（否则仅释放锁、车道仍死，后续事务照样排队挂死）。
                        // Q-1/Q-2 二轮：release 必须先于 recovery 执行——recovery 含 close/openPort
                        // 等可能阻塞的动作，若 recovery 在前且中途阻塞，release 被无限期推迟，
                        // currentKey 直接升级为幽灵锁（live 实证持锁 45min+）。锁释放与端口强拆
                        // 无顺序依赖，先释放锁是最小风险排序。
                        // F-43 清洗①：强拆先标记事务代已死（端口残留写闸门拦截被掐事务 delay
                        // 到点后的补发命令），再 release + 强拆。
                        source.markTransactionAborted();
                        source.release(key);
                        try {
                            source.recoverWedgedPort("transaction-hard-timeout");
                        } catch (Exception recoverEx) {
                            log.warn("Port wedge recovery failed for key {}: {}", key, recoverEx.getMessage());
                        }
                    } else {
                        source.release(key);
                    }
                }
            });
        } catch (Exception e) {
            source.release(key);
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    /**
     * 事务级硬超时：保证返回的 future 必然在 {@code timeoutMs} 内 complete
     * （成功则透传原 future 结果，超时则异常 {@link TimeoutException}）。
     *
     * <p>Java 8 无 {@code CompletableFuture.orTimeout}（Java 9+ 才有），故用
     * {@code applyToEither} 叠加一个由 {@link SerialTimeoutScheduler}（共享调度器）到点异常完成的计时 future 实现。
     *
     * <p>参考 modbus_rtu：modbus4j {@code master.setTimeout} 在 I/O 层保证操作必然完成（成功或超时），
     * 从而 {@code ModbusTransactionStrategy} 的 {@code whenComplete} 必触发 → release 必执行。
     * 纯 serial 无此 I/O 层保证，故在此事务层补强——保证 future 必然 complete →
     * {@code whenCompleteAsync} 必触发 → release 必执行，杜绝 B5（future 永不 complete 致端口锁泄漏）。
     *
     * @param future    原事务 future（可能永不 complete）
     * @param timeoutMs 硬超时（毫秒），调用方保证 &gt; 0（{@link #executeWithLambda} 入口已校验）
     * @return 必然 complete 的 future（成功透传结果，或异常 {@link TimeoutException}）
     */
    static CompletableFuture<Boolean> withHardTimeout(CompletableFuture<Boolean> future, long timeoutMs) {
        CompletableFuture<Boolean> timer = new CompletableFuture<>();
        ScheduledFuture<?> scheduled;
        try {
            scheduled = SerialTimeoutScheduler.schedule(() -> {
                TimeoutException ex = new TimeoutException(
                        "Transaction hard timeout after " + timeoutMs + " ms");
                // F-43 清洗①：硬超时竞速胜出时强制异常完成底层 operations future——挂起中的
                // 链头收到确定性终止信号（ CompletableFuture 只允许完成一次，正常完成已发生时
                // 此处 no-op），配合端口残留写闸门（markTransactionAborted）堵被掐事务剩余命令
                // 的补发复活路径（bugs/bug-record-20260826-093000 Q1）。
                future.completeExceptionally(ex);
                timer.completeExceptionally(ex);
            }, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException scheduleEx) {
            // 计时任务提交失败（如调度器排队达上限拒绝）：若不处理，timer 永不完成 → withHardTimeout
            // 永不完成 → whenComplete 不触发 → release 永不执行 → 端口锁成幽灵锁
            //（Q-1/Q-2 二轮根因之一：live 实证队列 4096 打满期间的幽灵锁）。此处立即异常完成
            // timer，保证完成链与 release 必达；非 TimeoutException，不误触发端口强拆。
            timer.completeExceptionally(new IllegalStateException(
                    "Hard-timeout timer submission failed, failing fast to guarantee release", scheduleEx));
            return future.applyToEither(timer, Function.identity());
        }
        // 引擎拒绝路径可能不抛异常、而是返回已异常完成的 future（任务体永不执行）——提交后立即
        // 复查：已异常完成且 timer 未完成，说明计时链已丢失，同样立即 fail-fast 保证 release。
        if (scheduled.isDone()) {
            try {
                scheduled.get();
            } catch (java.util.concurrent.ExecutionException rejected) {
                timer.completeExceptionally(new IllegalStateException(
                        "Hard-timeout timer rejected, failing fast to guarantee release", rejected.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 原 future 先完成（正常/异常）时，取消尚未触发的计时任务，避免无谓唤醒；
        // 计时先到时原 future 仍在挂起，cancel(false) 对已执行的 schedule 为 no-op
        return future.applyToEither(timer, Function.identity())
                .whenComplete((res, ex) -> scheduled.cancel(false));
    }
}    
