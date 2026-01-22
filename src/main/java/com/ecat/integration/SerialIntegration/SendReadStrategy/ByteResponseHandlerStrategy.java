package com.ecat.integration.SerialIntegration.SendReadStrategy;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.*;
import com.ecat.integration.SerialIntegration.Listener.SerialListenerPools;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.BytePooledSerialDataListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Byte-based response handler strategy.
 * Handles binary serial communication without String conversion.
 * Automatically detects the environment and uses either interrupt-driven mode
 * (production) or polling mode (testing) for response handling.
 * 
 * @apiNote The ResponseHandlerStrategy strictly ensures that the total time elapsed from data reception 
 * to the completion of find full frame data processing is within READ_TIMEOUT_MS. If this threshold is exceeded, execution 
 * will be terminated at the receive and find frame point and relevant resources will be reclaimed. 
 * For long-running tasks, the task initiator shall hand over the results to its own asynchronous threads 
 * for processing, so as to ensure the rapid completion of interface data access.
 *
 * @param <T> The type of the context object used during response handling.
 * @author coffee
 */
public class ByteResponseHandlerStrategy<T> {

    private static final Log log = LogFactory.getLogger(ByteResponseHandlerStrategy.class);

    private final SerialSource serialSource;
    private final Function<ByteResponseHandlingContext<T>, Boolean> processResponseFunction;
    private final Function<byte[], byte[]> checkResponseFunction;
    private final Function<Throwable, Boolean> handleExceptionFunction;
    private final boolean useLegacyMode;
    private final String portName;
    private final long timeoutMs;  // 超时时间（毫秒）

    // 预定义的超时异常
    private static final TimeoutException TIMEOUT_EXCEPTION =
        new TimeoutException("Serial response timeout");

    /**
     * Constructs a new ByteResponseHandlerStrategy with the specified parameters.
     * The strategy automatically detects whether to use interrupt-driven or polling mode.
     * Uses default timeout of {@link Const#READ_TIMEOUT_MS}.
     *
     * @param serialSource The SerialSource for communication
     * @param processResponseFunction A function that processes the response context and returns success/failure
     * @param checkResponseFunction A function that checks the byte array and returns validated bytes or null
     * @param handleExceptionFunction A function that handles exceptions
     */
    public ByteResponseHandlerStrategy(
            SerialSource serialSource,
            Function<ByteResponseHandlingContext<T>, Boolean> processResponseFunction,
            Function<byte[], byte[]> checkResponseFunction,
            Function<Throwable, Boolean> handleExceptionFunction) {
        this(serialSource, processResponseFunction, checkResponseFunction,
             handleExceptionFunction, Const.READ_TIMEOUT_MS);
    }

    /**
     * Constructs a new ByteResponseHandlerStrategy with custom timeout.
     * The strategy automatically detects whether to use interrupt-driven or polling mode.
     *
     * @param serialSource The SerialSource for communication
     * @param processResponseFunction A function that processes the response context and returns success/failure
     * @param checkResponseFunction A function that checks the byte array and returns validated bytes or null
     * @param handleExceptionFunction A function that handles exceptions
     * @param timeoutMs Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeoutMs is not positive
     */
    public ByteResponseHandlerStrategy(
            SerialSource serialSource,
            Function<ByteResponseHandlingContext<T>, Boolean> processResponseFunction,
            Function<byte[], byte[]> checkResponseFunction,
            Function<Throwable, Boolean> handleExceptionFunction,
            long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.serialSource = serialSource;
        this.processResponseFunction = processResponseFunction;
        this.checkResponseFunction = checkResponseFunction;
        this.handleExceptionFunction = handleExceptionFunction;
        this.portName = serialSource == null ? "null_serial_source" : serialSource.getPortName();
        this.timeoutMs = timeoutMs;
        this.useLegacyMode = detectLegacyModeRequired();
    }

    /**
     * Handles the response for the given context.
     *
     * @param context the response handling context
     * @return CompletableFuture indicating success or failure
     */
    public CompletableFuture<Boolean> handleResponse(ByteResponseHandlingContext<T> context) {
        if (useLegacyMode) {
            return handleResponseLegacy(context);
        } else {
            return handleResponseInterrupt(context);
        }
    }

    /**
     * 传统轮询模式处理响应（测试兼容）
     */
    private CompletableFuture<Boolean> handleResponseLegacy(ByteResponseHandlingContext<T> context) {
        // readDataRecursively 已经在 SerialAsyncExecutor 中执行
        // 使用 thenApply（而非 thenApplyAsync）保持在该线程中执行，避免线程切换
        return readDataRecursively(context)
               .thenApply(processResponseFunction)
               .exceptionally(handleExceptionFunction);
    }

    /**
     * 中断驱动模式处理响应（生产环境）
     * 优化版本：使用端口隔离的线程池和对象池，异步处理响应不阻塞串口线程
     */
    private CompletableFuture<Boolean> handleResponseInterrupt(ByteResponseHandlingContext<T> context) {
        long handlerStartTime = System.currentTimeMillis();
        log.debug("handleResponseInterrupt starting for port: {} at {}, SerialAsyncExecutor status: {}",
                portName, handlerStartTime, SerialAsyncExecutor.getStatus());

        // 创建响应等待的 Future
        CompletableFuture<ByteResponseHandlingContext<T>> responseFuture = new CompletableFuture<>();

        // 从对象池获取监听器
        BytePooledSerialDataListener listener = SerialListenerPools.BYTE_POOL.acquire();

        log.debug("剩余：{}", SerialListenerPools.BYTE_POOL.getAvailableCount());

        // 如果池中没有可用监听器，降级使用临时监听器
        if (listener == null) {
            log.debug("Port {}: No pooled byte listener available, creating temporary listener", portName);
            return handleResponseInterruptWithTempListener(context);
        }

        // 重置监听器状态
        listener.reset(context, responseFuture, serialSource, checkResponseFunction);

        // 注册监听器
        serialSource.addDataListener(listener);

        // 设置超时 - 使用端口隔离的线程池
        // 超时任务只标记和移除监听器，不释放（由 whenCompleteAsync 统一释放）
        ScheduledFuture<?> timeoutTask = SerialTimeoutScheduler.schedule(
            portName,
            () -> {
                if (responseFuture.completeExceptionally(TIMEOUT_EXCEPTION)) {
                    serialSource.removeDataListener(listener);
                }
            },
            this.timeoutMs,
            TimeUnit.MILLISECONDS
        );

        // 使用异步处理链，在独立线程池中执行，不阻塞串口线程
        return responseFuture
            .handleAsync((result, ex) -> {
                // 在独立线程中处理响应和异常，不阻塞串口线程
                long responseDuration = System.currentTimeMillis() - handlerStartTime;
                if (ex != null) {
                    log.warn("Response handling for port {} completed exceptionally after {} ms: {}",
                            portName, responseDuration, ex.getMessage());
                    return handleExceptionFunction.apply(ex);
                } else {
                    log.debug("Response handling for port {} completed successfully after {} ms",
                            portName, responseDuration);
                    return processResponseFunction.apply(result);
                }
            }, SerialAsyncExecutor.getExecutor())
            .whenCompleteAsync((res, ex) -> {
                // 统一清理：只在这里调用一次 removeDataListener 和 release
                if (timeoutTask != null && !timeoutTask.isDone()) {
                    timeoutTask.cancel(true);
                }
                // 确保移除监听器（可能已移除，但 CopyOnWriteArrayList.remove() 是幂等的）
                serialSource.removeDataListener(listener);
                // 释放监听器回池
                SerialListenerPools.BYTE_POOL.release(listener);
            }, SerialAsyncExecutor.getExecutor());
    }

    /**
     * 降级处理：使用临时监听器（对象池已满时）
     */
    private CompletableFuture<Boolean> handleResponseInterruptWithTempListener(ByteResponseHandlingContext<T> context) {
        CompletableFuture<ByteResponseHandlingContext<T>> responseFuture = new CompletableFuture<>();

        // 创建临时监听器（降级方案）
        SerialDataListener listener = new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                // 直接追加字节数据
                context.getReceiveBuffer().write(data, 0, length);

                // 检查响应是否完整
                byte[] bufferContent = context.getReceiveBytes();
                byte[] checkResult = checkResponseFunction.apply(bufferContent);

                if (checkResult != null) {
                    context.getFinishedFlag().set(true);
                    responseFuture.complete(context);
                    serialSource.removeDataListener(this);
                }
            }

            @Override
            public void onError(Exception ex) {
                responseFuture.completeExceptionally(ex);
                serialSource.removeDataListener(this);
            }
        };

        // 注册监听器
        serialSource.addDataListener(listener);

        // 设置超时
        ScheduledFuture<?> timeoutTask = SerialTimeoutScheduler.schedule(
            portName,
            () -> {
                responseFuture.completeExceptionally(TIMEOUT_EXCEPTION);
                serialSource.removeDataListener(listener);
            },
            this.timeoutMs,
            TimeUnit.MILLISECONDS
        );

        // 使用异步处理链
        return responseFuture
            .handleAsync((result, ex) -> {
                if (ex != null) {
                    serialSource.removeDataListener(listener);
                    return handleExceptionFunction.apply(ex);
                } else {
                    return processResponseFunction.apply(result);
                }
            }, SerialAsyncExecutor.getExecutor())
            .whenCompleteAsync((res, ex) -> {
                if (timeoutTask != null && !timeoutTask.isDone()) {
                    timeoutTask.cancel(true);
                }
                serialSource.removeDataListener(listener);
            }, SerialAsyncExecutor.getExecutor());
    }

    /**
     * 递归读取数据（轮询模式）
     * 仅用于测试环境，性能较差不要用于生产环境
     */
    private CompletableFuture<ByteResponseHandlingContext<T>> readDataRecursively(ByteResponseHandlingContext<T> context) {
        // 检查是否已完成
        if (context.getFinishedFlag().get()) {
            return CompletableFuture.completedFuture(context);
        }

        // 检查是否超时 - 超时时抛出 TimeoutException
        if (System.currentTimeMillis() - context.getStartTime().get() >= this.timeoutMs) {
            CompletableFuture<ByteResponseHandlingContext<T>> future = new CompletableFuture<>();
            future.completeExceptionally(TIMEOUT_EXCEPTION);
            return future;
        }

        // 休眠一段时间，避免过于频繁地读取数据
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompletableFuture<ByteResponseHandlingContext<T>> future = new CompletableFuture<>();
            future.completeExceptionally(new InterruptedException("Interrupted during polling"));
            return future;
        }

        // 使用 asyncReadDataBytes() 异步获取字节数据
        // asyncReadDataBytes() 已经在 SerialAsyncExecutor 中执行
        // 使用 thenCompose（而非 thenComposeAsync）保持在该线程中执行，避免线程切换
        // 这对于测试环境的 Mock 验证很重要
        return serialSource.asyncReadDataBytes()
               .thenApply(data -> {
                   context.getReceiveBuffer().write(data, 0, data.length);
                   byte[] bufferContent = context.getReceiveBytes();
                   if (checkResponseFunction.apply(bufferContent) != null) {
                       context.getFinishedFlag().set(true);
                   }
                   return context;
               })
               .thenCompose(buffer -> readDataRecursively(context));
    }

    /**
     * 检测是否需要使用传统轮询模式
     * 主要用于测试环境检测
     */
    private boolean detectLegacyModeRequired() {
        // 1. 检查堆栈中是否包含测试框架
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.contains("junit") ||
                className.contains("mockito") ||
                className.contains("test") ||
                className.contains("hamcrest") ||
                className.contains("org.gradle") ||
                className.contains("sun.reflect")) {
                return true;
            }
        }

        // 2. 检查 SerialSource 是否为 Mock 或测试模式
        if (serialSource != null) {
            if (serialSource.isTestMode()) {
                return true;
            }

            String sourceClassName = serialSource.getClass().getName();
            if (sourceClassName.contains("Mock") ||
                sourceClassName.contains("Proxy") ||
                sourceClassName.contains("$")) {
                return true;
            }
        }

        // 3. 检查系统属性
        String testMode = System.getProperty("test.mode", "false");
        if (Boolean.parseBoolean(testMode)) {
            return true;
        }

        // 4. 检查 JUnit 相关的系统属性
        String junit = System.getProperty("junit", "");
        if (!junit.isEmpty()) {
            return true;
        }

        // 5. 默认使用中断模式（生产环境）
        return false;
    }
}
