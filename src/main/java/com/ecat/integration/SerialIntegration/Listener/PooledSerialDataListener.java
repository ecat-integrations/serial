package com.ecat.integration.SerialIntegration.Listener;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import java.util.concurrent.atomic.AtomicInteger;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ResponseHandlingContext;

/**
 * 可复用的串口数据监听器
 * 通过对象池管理生命周期，减少对象创建开销
 *
 * @author coffee
 */
public class PooledSerialDataListener implements SerialDataListener, PoolableListener {

    private static final Log log = LogFactory.getLogger(PooledSerialDataListener.class);
    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    // 实例ID，用于跟踪
    private final int instanceId;
    // 池化状态
    private volatile boolean isInUse = false;

    // 业务状态（每次使用时重置）
    private ResponseHandlingContext<?> context;
    private CompletableFuture<?> responseFuture;
    private SerialSource serialSource;
    private Function<String, String> checkResponseFunction;

    // 创建和最后使用时间，用于检测泄漏
    private long lastUsedTime;

    // 构造函数
    public PooledSerialDataListener() {
        this.instanceId = instanceCounter.incrementAndGet();
        log.debug("创建监听器实例 #{}, 线程: {}", instanceId, Thread.currentThread().getName());
    }

    /**
     * 重置监听器状态，准备复用
     *
     * @param context 响应处理上下文
     * @param responseFuture 响应Future
     * @param serialSource 串口源
     * @param checkResponseFunction 响应检查函数
     */
    public void reset(ResponseHandlingContext<?> context,
                     CompletableFuture<?> responseFuture,
                     SerialSource serialSource,
                     Function<String, String> checkResponseFunction) {
        log.debug("重置监听器 #{}, 串口: {}, 上下文: {}",
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

    /**
     * 清理监听器状态，准备回收到对象池
     */
    public void cleanup() {
        log.debug("清理监听器 #{}, 已使用: {} ms",
                instanceId, System.currentTimeMillis() - lastUsedTime);

        this.context = null;
        this.responseFuture = null;
        this.serialSource = null;
        this.checkResponseFunction = null;
        this.isInUse = false;
    }

    /**
     * 检查监听器是否正在使用
     *
     * @return 如果正在使用返回true
     */
    public boolean isInUse() {
        return isInUse;
    }

    @Override
    public void onDataReceived(byte[] data, int length) {
        // 安全检查：防止已被回收的监听器被调用
        if (!isInUse || context == null || responseFuture == null || serialSource == null) {
            // ECAT log handles level checking automatically
            log.warn("监听器 #{} 在无效状态下收到数据，忽略. isInUse={}, context={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, context != null ? "exists" : "null",
                responseFuture != null ? "exists" : "null",
                serialSource != null ? serialSource.getPortName() : "null");
            return;
        }

        try {
            String receivedData = new String(data, 0, length);
            log.trace("监听器 #{} 收到数据 [{}]: {}",
                instanceId, serialSource.getPortName(), receivedData.trim());

            // 追加接收到的数据
            context.getReceiveBuffer().append(receivedData);

            // 检查响应是否完整
            String bufferContent = context.getReceiveBuffer().toString();
            String checkResult = checkResponseFunction.apply(bufferContent);

            if (checkResult != null) {
                log.debug("监听器 #{} 响应完整，移除监听器. 串口: {}, 响应长度: {}",
                    instanceId, serialSource.getPortName(), checkResult.length());

                // 设置完成标志
                context.getFinishedFlag().set(true);

                // 完成Future
                @SuppressWarnings("unchecked")
                CompletableFuture<ResponseHandlingContext<?>> typedFuture =
                    (CompletableFuture<ResponseHandlingContext<?>>) responseFuture;
                typedFuture.complete(context);

                // 仅在响应完整时移除监听器（保留监听器以接收分片数据的后续部分）
                serialSource.removeDataListener(this);
            }
            // 如果响应不完整，保留监听器继续接收后续数据包
            // serialSource.removeDataListener(this);

        } catch (Exception e) {
            log.warn("监听器 #{} 处理数据时发生异常: {}", instanceId, e);
            // 异常时也要移除监听器
            try {
                responseFuture.completeExceptionally(e);
                serialSource.removeDataListener(this);
            } catch (Exception removeEx) {
                log.warn("监听器 #{} 移除时发生异常: {}", instanceId, removeEx);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        // 安全检查
        if (!isInUse || responseFuture == null || serialSource == null) {
            log.warn("监听器 #{} 在无效状态下收到错误，忽略. isInUse={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, responseFuture != null ? "exists" : "null",
                serialSource != null ? serialSource.getPortName() : "null");
            return;
        }

        log.warn("监听器 #{} 收到错误: {}, 串口: {}",
                instanceId, ex.getMessage(),
                serialSource != null ? serialSource.getPortName() : "unknown");

        responseFuture.completeExceptionally(ex);
        serialSource.removeDataListener(this);
    }

    // 添加调试方法
    @Override
    public String toString() {
        return String.format("PooledListener#%d[inUse=%s, port=%s]",
            instanceId, isInUse,
            serialSource != null ? serialSource.getPortName() : "null");
    }
}
