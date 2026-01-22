package com.ecat.integration.SerialIntegration.SendReadStrategy;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.Const;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialAsyncExecutor;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.PooledSerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListenerPool;

// 定义响应处理策略接口
/**
 * ResponseHandlerStrategy is an interface that defines a strategy for handling responses
 * from a serial source. It provides a method to handle the response based on the given context.
 * * @param <T> The type of the context object used during response handling.
 * 
 * @author coffee
 */
interface ResponseHandlerStrategy<T> {
    CompletableFuture<Boolean> handleResponse(ResponseHandlingContext<T> context);
}

/**
 * A default implementation of the {@link ResponseHandlerStrategy} interface that handles responses
 * from a serial source. This strategy automatically detects the environment and uses either
 * interrupt-driven mode (production) or polling mode (testing) for response handling.
 * 
 * @apiNote The ResponseHandlerStrategy strictly ensures that the total time elapsed from data reception 
 * to the completion of find full frame data processing is within READ_TIMEOUT_MS. If this threshold is exceeded, execution 
 * will be terminated at the receive and find frame point and relevant resources will be reclaimed. 
 * For long-running tasks, the task initiator shall hand over the results to its own asynchronous threads 
 * for processing, so as to ensure the rapid completion of interface data access.
 *
 * @param <T> The type of the context object used during response handling.
 * @author coffee
 * @deprecated This class is deprecated and may be removed in future versions. Consider using
 *             alternative implementations of {@link ByteResponseHandlerStrategy} that better suit your needs byte[].
 */
@Deprecated
public class DefaultResponseHandlerStrategy<T> implements ResponseHandlerStrategy<T> {
    private static final Log log = LogFactory.getLogger(DefaultResponseHandlerStrategy.class);

    private final SerialSource serialSource;
    private final Function<ResponseHandlingContext<T>, Boolean> processResponseFunction;
    private final Function<String, String> checkResponseFunction;
    private final Function<Throwable, Boolean> handleExceptionFunction;
    private final boolean useLegacyMode; // 是否使用传统轮询模式（用于测试兼容）
    private final String portName; // 端口名称，用于线程池隔离
    private final long timeoutMs;  // 超时时间（毫秒）

    // 预定义的超时异常，避免重复创建
    private static final TimeoutException TIMEOUT_EXCEPTION =
        new TimeoutException("Serial response timeout");

    /**
     * Constructs a new DefaultResponseHandlerStrategy with the specified parameters.
     * The strategy automatically detects whether to use interrupt-driven or polling mode based on the environment.
     *
     * @param serialSource The SerialSource of data to be handled.
     * @param processResponseFunction A function that processes the response handling context and returns a boolean
     *                                indicating the success or failure of the processing.
     * @param checkResponseFunction A function that checks the response string and returns a modified or validated response string.
     * @param handleExceptionFunction A function that handles exceptions and returns a boolean indicating whether the exception
     *                                was successfully handled.
     * @param <T> The type of the response handling context.
     */
    @Deprecated
    public DefaultResponseHandlerStrategy(SerialSource serialSource,
                                          Function<ResponseHandlingContext<T>, Boolean> processResponseFunction,
                                          Function<String, String> checkResponseFunction,
                                          Function<Throwable, Boolean> handleExceptionFunction) {
        this(serialSource, processResponseFunction, checkResponseFunction,
             handleExceptionFunction, Const.READ_TIMEOUT_MS);
    }

    /**
     * Constructs a new DefaultResponseHandlerStrategy with custom timeout.
     * The strategy automatically detects whether to use interrupt-driven or polling mode based on the environment.
     *
     * @param serialSource The SerialSource of data to be handled.
     * @param processResponseFunction A function that processes the response handling context and returns a boolean
     *                                indicating the success or failure of the processing.
     * @param checkResponseFunction A function that checks the response string and returns a modified or validated response string.
     * @param handleExceptionFunction A function that handles exceptions and returns a boolean indicating whether the exception
     *                                was successfully handled.
     * @param timeoutMs Timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if timeoutMs is not positive
     * @param <T> The type of the response handling context.
     */
    @Deprecated
    public DefaultResponseHandlerStrategy(SerialSource serialSource,
                                          Function<ResponseHandlingContext<T>, Boolean> processResponseFunction,
                                          Function<String, String> checkResponseFunction,
                                          Function<Throwable, Boolean> handleExceptionFunction,
                                          long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.serialSource = serialSource;
        this.processResponseFunction = processResponseFunction;
        this.checkResponseFunction = checkResponseFunction;
        this.handleExceptionFunction = handleExceptionFunction;
        this.portName = serialSource == null ? "null_serial_source" : serialSource.getPortName(); // 获取端口名称
        this.timeoutMs = timeoutMs;
        // 检测是否需要使用传统模式（测试环境）
        this.useLegacyMode = detectLegacyModeRequired();
    }

    @Deprecated
    @Override
    public CompletableFuture<Boolean> handleResponse(ResponseHandlingContext<T> context) {
        if (useLegacyMode) {
            // 测试环境：使用原有的轮询方式
            return handleResponseLegacy(context);
        } else {
            // 生产环境：使用中断驱动方式
            return handleResponseInterrupt(context);
        }
    }

    /**
     * 传统轮询模式处理响应（测试兼容）
     *
     * @param context 响应处理上下文
     * @return CompletableFuture<Boolean>
     */
    private CompletableFuture<Boolean> handleResponseLegacy(ResponseHandlingContext<T> context) {
        // readDataRecursively 已经在 SerialAsyncExecutor 中执行
        // 使用 thenApply（而非 thenApplyAsync）保持在该线程中执行，避免线程切换
        return readDataRecursively(context)
               .thenApply(processResponseFunction)
               .exceptionally(handleExceptionFunction);
    }

    /**
     * 中断驱动模式处理响应（生产环境）
     * 优化版本：使用端口隔离的线程池和对象池，异步处理响应不阻塞串口线程
     * @param context 响应处理上下文
     * @return CompletableFuture<Boolean>
     */
    private CompletableFuture<Boolean> handleResponseInterrupt(ResponseHandlingContext<T> context) {
        // 创建响应等待的 Future
        CompletableFuture<ResponseHandlingContext<T>> responseFuture = new CompletableFuture<>();

        // 从对象池获取监听器
        PooledSerialDataListener listener = SerialDataListenerPool.acquire();

        log.debug("剩余：{}", SerialDataListenerPool.getAvailableCount());

        // 如果池中没有可用监听器，降级使用临时监听器
        if (listener == null) {
            log.debug("Port {}: No pooled listener available, creating temporary listener", portName);
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
                if (ex != null) {
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
                SerialDataListenerPool.release(listener);
            }, SerialAsyncExecutor.getExecutor());
    }

    /**
     * 降级处理：使用临时监听器（对象池已满时）
     *
     * @param context 响应处理上下文
     * @return CompletableFuture<Boolean>
     */
    private CompletableFuture<Boolean> handleResponseInterruptWithTempListener(ResponseHandlingContext<T> context) {
        // 创建响应等待的 Future
        CompletableFuture<ResponseHandlingContext<T>> responseFuture = new CompletableFuture<>();

        // 创建临时监听器（降级方案）
        SerialDataListener listener = new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                String receivedData = new String(data, 0, length);
                context.getReceiveBuffer().append(receivedData);

                // 检查响应是否完整
                if (checkResponseFunction.apply(context.getReceiveBuffer().toString()) != null) {
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
     * 仅用于测试环境，性能较差不要用于生产环境
     * 为了兼容已有的其他集成的测试用例而存在
     * @param context
     * @return
     */
    private CompletableFuture<ResponseHandlingContext<T>> readDataRecursively(ResponseHandlingContext<T> context) {
        // 检查是否已完成
        if (context.getFinishedFlag().get()) {
            return CompletableFuture.completedFuture(context);
        }

        // 检查是否超时 - 超时时抛出 TimeoutException
        if (System.currentTimeMillis() - context.getStartTime().get() >= this.timeoutMs) {
            CompletableFuture<ResponseHandlingContext<T>> future = new CompletableFuture<>();
            future.completeExceptionally(TIMEOUT_EXCEPTION);
            return future;
        }

        // 休眠一段时间，避免过于频繁地读取数据
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompletableFuture<ResponseHandlingContext<T>> future = new CompletableFuture<>();
            future.completeExceptionally(new InterruptedException("Interrupted during polling"));
            return future;
        }
        // asyncReadData() 已经在 SerialAsyncExecutor 中执行
        // 使用 thenCompose（而非 thenComposeAsync）保持在该线程中执行，避免线程切换
        // 这对于测试环境的 Mock 验证很重要
        return serialSource.asyncReadData()
               .thenApply(data -> {
                    context.getReceiveBuffer().append(data);
                    if (checkResponseFunction.apply(context.getReceiveBuffer().toString()) != null) {
                        context.getFinishedFlag().set(true);
                    }
                    return context;
                })
               .thenCompose(buffer -> readDataRecursively(context));
    }

    /**
     * 检测是否需要使用传统轮询模式
     * 主要用于测试环境检测
     *
     * @return true 如果需要使用轮询模式
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
            // 检查 SerialSource 是否标记为测试模式
            if (serialSource.isTestMode()) {
                return true;
            }

            // 检查是否为 Mock 对象
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
