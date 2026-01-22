package com.ecat.integration.SerialIntegration.Listener;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Pooled serial data listener for binary data.
 * Mirrors PooledSerialDataListener but uses byte[] instead of String.
 *
 * @author coffee
 */
public class BytePooledSerialDataListener implements SerialDataListener, PoolableListener {

    private static final Log log = LogFactory.getLogger(BytePooledSerialDataListener.class);
    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    private final int instanceId;
    private volatile boolean isInUse = false;

    private ByteResponseHandlingContext<?> context;
    private CompletableFuture<?> responseFuture;
    private com.ecat.integration.SerialIntegration.SerialSource serialSource;
    private Function<byte[], byte[]> checkResponseFunction;

    private long lastUsedTime;

    /**
     * Creates a new byte pooled listener.
     */
    public BytePooledSerialDataListener() {
        this.instanceId = instanceCounter.incrementAndGet();
        log.debug("创建字节监听器实例 #{}, 线程: {}", instanceId, Thread.currentThread().getName());
    }

    /**
     * Resets the listener state for reuse.
     *
     * @param context the response handling context
     * @param responseFuture the response future
     * @param serialSource the serial source
     * @param checkResponseFunction the function to check response completeness
     */
    public void reset(ByteResponseHandlingContext<?> context,
                     CompletableFuture<?> responseFuture,
                     com.ecat.integration.SerialIntegration.SerialSource serialSource,
                     Function<byte[], byte[]> checkResponseFunction) {
        log.debug("重置字节监听器 #{}, 串口: {}, 上下文: {}",
                instanceId,
                serialSource != null ? serialSource.getPortName() : "null",
                context != null ? context.toString() : "null");

        this.context = context;
        this.responseFuture = responseFuture;
        this.serialSource = serialSource;
        this.checkResponseFunction = checkResponseFunction;
        this.lastUsedTime = System.currentTimeMillis();
        this.isInUse = true;
    }

    @Override
    public void cleanup() {
        log.debug("清理字节监听器 #{}, 已使用: {} ms",
                instanceId, System.currentTimeMillis() - lastUsedTime);

        this.context = null;
        this.responseFuture = null;
        this.serialSource = null;
        this.checkResponseFunction = null;
        this.isInUse = false;
    }

    @Override
    public boolean isInUse() {
        return isInUse;
    }

    @Override
    public void onDataReceived(byte[] data, int length) {
        if (!isInUse || context == null || responseFuture == null || serialSource == null) {
            log.warn("字节监听器 #{} 在无效状态下收到数据，忽略. isInUse={}, context={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, context != null ? "exists" : "null",
                responseFuture != null ? "exists" : "null",
                serialSource != null ? serialSource.getPortName() : "null");
            return;
        }

        try {
            // 直接追加字节数据
            context.getReceiveBuffer().write(data, 0, length);

            log.trace("字节监听器 #{} 收到 {} 字节, 串口: {}",
                instanceId, length, serialSource.getPortName());

            // 检查响应是否完整
            byte[] bufferContent = context.getReceiveBytes();
            byte[] checkResult = checkResponseFunction.apply(bufferContent);

            if (checkResult != null) {
                log.debug("字节监听器 #{} 响应完整 ({} 字节), 移除监听器. 串口: {}",
                    instanceId, bufferContent.length, serialSource.getPortName());

                context.getFinishedFlag().set(true);

                @SuppressWarnings("unchecked")
                CompletableFuture<ByteResponseHandlingContext<?>> typedFuture =
                    (CompletableFuture<ByteResponseHandlingContext<?>>) responseFuture;
                typedFuture.complete(context);
                // 只在响应完整时才移除监听器
                if (serialSource != null) {
                    serialSource.removeDataListener(this);
                }
            }
            // 如果响应不完整，继续等待更多数据，不移除监听器

        } catch (Exception e) {
            log.warn("字节监听器 #{} 处理数据时发生异常: {}", instanceId, e);
            try {
                if (responseFuture != null) {
                    responseFuture.completeExceptionally(e);
                }
                if (serialSource != null) {
                    serialSource.removeDataListener(this);
                }
            } catch (Exception removeEx) {
                log.warn("字节监听器 #{} 移除时发生异常: {}", instanceId, removeEx);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        if (!isInUse || responseFuture == null || serialSource == null) {
            log.warn("字节监听器 #{} 在无效状态下收到错误，忽略. isInUse={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, responseFuture != null ? "exists" : "null",
                serialSource != null ? serialSource.getPortName() : "null");
            return;
        }

        log.warn("字节监听器 #{} 收到错误: {}, 串口: {}",
                instanceId, ex.getMessage(),
                serialSource != null ? serialSource.getPortName() : "unknown");

        if (responseFuture != null) {
            responseFuture.completeExceptionally(ex);
        }
        if (serialSource != null) {
            serialSource.removeDataListener(this);
        }
    }

    @Override
    public String toString() {
        return String.format("BytePooledListener#%d[inUse=%s, port=%s]",
            instanceId, isInUse,
            serialSource != null ? serialSource.getPortName() : "null");
    }
}
