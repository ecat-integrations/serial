package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;


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
     * 事务级硬超时计时器：单线程 daemon 调度池，到点把计时 future 异常完成（{@link TimeoutException}）。
     * 全类共享（非每事务新建），daemon 线程不阻止 JVM 退出。
     */
    private static final ScheduledExecutorService HARD_TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "serial-tx-hard-timeout");
                t.setDaemon(true);
                return t;
            });

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
     * @return 事务级硬超时（毫秒）；source 为 null 或其串口超时无效时回退 Const.READ_TIMEOUT_MS × 倍数
     */
    static long resolveDefaultTransactionTimeoutMs(SerialSource source) {
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
     * <p><b>正确范式</b>（对齐 modbus 设备及 sailhero XH*、PM3000E 等既有设备 —— 调度线程零阻塞）：
     * <pre>{@code
     * scheduledFuture = getScheduledExecutor().scheduleWithFixedDelay(() ->
     *     SerialTransactionStrategy.executeWithLambda(serialSource, source ->
     *             getRealData().thenCompose(v -> getStatusData()))
     *         .whenComplete((r, e) -> { if (e != null) log.error("...poll error", e); }),
     *     0, 5, TimeUnit.SECONDS);
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
        if (key!=null) {
            try {
                long operationStartTime = System.currentTimeMillis();
                CompletableFuture<Boolean> operations = lambda.apply(source);
                // B5 修复：施加事务级硬超时，保证 future 必然 complete → whenCompleteAsync 必触发 → release 必执行
                CompletableFuture<Boolean> withTimeout = withHardTimeout(operations, transactionTimeoutMs);
                return withTimeout.whenCompleteAsync((res, ex) -> {
                    long duration = System.currentTimeMillis() - operationStartTime;
                    try {
                        if (ex != null) {
                            log.error("Transaction with key: {} failed after {} ms, exception: {}",
                                    key, duration, ex.getMessage(), ex);
                        }
                    } finally {
                        source.release(key);
                    }
                }, SerialAsyncExecutor.getExecutor());
            } catch (Exception e) {
                source.release(key);
                CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(e);
                return failedFuture;
            }
        } else {
            log.error("Failed to acquire lock");
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Failed to acquire lock"));
            return failedFuture;
        }
    }

    /**
     * 事务级硬超时：保证返回的 future 必然在 {@code timeoutMs} 内 complete
     * （成功则透传原 future 结果，超时则异常 {@link TimeoutException}）。
     *
     * <p>Java 8 无 {@code CompletableFuture.orTimeout}（Java 9+ 才有），故用
     * {@code applyToEither} 叠加一个由 {@link ScheduledExecutorService} 到点异常完成的计时 future 实现。
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
        ScheduledFuture<?> scheduled = HARD_TIMEOUT_SCHEDULER.schedule(
                () -> timer.completeExceptionally(new TimeoutException(
                        "Transaction hard timeout after " + timeoutMs + " ms")),
                timeoutMs, TimeUnit.MILLISECONDS);
        // 原 future 先完成（正常/异常）时，取消尚未触发的计时任务，避免无谓唤醒；
        // 计时先到时原 future 仍在挂起，cancel(false) 对已执行的 schedule 为 no-op
        return future.applyToEither(timer, Function.identity())
                .whenComplete((res, ex) -> scheduled.cancel(false));
    }
}    
