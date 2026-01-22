package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletableFuture;
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
 *   <li>Logs errors and thread information for debugging purposes.</li>
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

    public static CompletableFuture<Boolean> executeWithLambda(SerialSource source, Function<SerialSource, CompletableFuture<Boolean>> lambda) {
        String key = source.acquire();
        if (key!=null) {
            // 打印当前线程名称
            // log.info("Current thread: " + Thread.currentThread().getName());
            try {
                long operationStartTime = System.currentTimeMillis();
                log.info("Transaction starting with key: {} at {}", key, operationStartTime);
                CompletableFuture<Boolean> operations = lambda.apply(source);
                // return operations.thenApply(v -> {
                //     // 这里会在同一个线程中执行
                //     source.unlock();
                //     return null;
                // });
                // return operations.thenCompose(result -> {
                //     try {
                //         return CompletableFuture.completedFuture(result);
                //     } finally {
                //         // 打印当前线程名称
                //         log.info("Current thread: " + Thread.currentThread().getName());
                //         source.unlock(key);
                //     }
                // });
                return operations.whenCompleteAsync((res, ex) -> {
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
}    
