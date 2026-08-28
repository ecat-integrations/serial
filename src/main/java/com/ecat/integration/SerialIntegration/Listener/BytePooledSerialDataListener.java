package com.ecat.integration.SerialIntegration.Listener;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.SerialSource;
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
    private SerialSource serialSource;
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
                     SerialSource serialSource,
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
        // 入口快照：监听器业务字段在 reset() 之后、cleanup() 之前，随时可能被池释放链
        // （ByteResponseHandlerStrategy.handleResponseInterrupt 的 whenCompleteAsync →
        // pool.release → cleanup）在另一线程并发清空——typedFuture.complete() 一经执行，
        // 监听器生命周期即移交该异步清理链，而通知线程（jSerialComm 事件线程）还要继续
        // 走完本方法。守卫校验的是快照，后续所有读写也必须用同一组引用，否则守卫与
        // 使用之间字段被清空即静默跳过移除（注册残留）或 NPE。
        ByteResponseHandlingContext<?> currentContext = context;
        CompletableFuture<?> currentFuture = responseFuture;
        SerialSource currentSource = serialSource;
        Function<byte[], byte[]> currentCheck = checkResponseFunction;

        if (!isInUse || currentContext == null || currentFuture == null || currentSource == null) {
            log.warn("字节监听器 #{} 在无效状态下收到数据，忽略. isInUse={}, context={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, currentContext != null ? "exists" : "null",
                currentFuture != null ? "exists" : "null",
                currentSource != null ? currentSource.getPortName() : "null");
            return;
        }

        try {
            // 直接追加字节数据
            currentContext.getReceiveBuffer().write(data, 0, length);

            log.trace("字节监听器 #{} 收到 {} 字节, 串口: {}",
                instanceId, length, currentSource.getPortName());

            // 检查响应是否完整
            byte[] bufferContent = currentContext.getReceiveBytes();
            byte[] checkResult = currentCheck.apply(bufferContent);

            if (checkResult != null) {
                log.debug("字节监听器 #{} 响应完整 ({} 字节), 移除监听器. 串口: {}",
                    instanceId, bufferContent.length, currentSource.getPortName());

                currentContext.getFinishedFlag().set(true);

                @SuppressWarnings("unchecked")
                CompletableFuture<ByteResponseHandlingContext<?>> typedFuture =
                    (CompletableFuture<ByteResponseHandlingContext<?>>) currentFuture;
                // P1（29 号 M3）：IO 线程只读字节+组帧+投递——业务 finalize（complete
                // responseFuture，processResponse 续链由此在 Core Worker 上触发）经
                // Core.submit 投递，不在本（sweeper）线程内联执行。
                currentSource.submitInboundFrame(bufferContent, () -> typedFuture.complete(currentContext));
                // 只在响应完整时移除监听器。按监听器身份移除且幂等
                // （CopyOnWriteArrayList.remove），与释放链 whenCompleteAsync 中的
                // 兜底移除互不冲突。
                currentSource.removeDataListener(this);
            }
            // 如果响应不完整，继续等待更多数据，不移除监听器

        } catch (Exception e) {
            // slf4j 约定：Throwable 作最后一个参数自动绑定堆栈，不能为它再写 {} 占位符
            log.warn("字节监听器 #{} 处理数据时发生异常", instanceId, e);
            try {
                currentFuture.completeExceptionally(e);
                currentSource.removeDataListener(this);
            } catch (Exception removeEx) {
                log.warn("字节监听器 #{} 移除时发生异常", instanceId, removeEx);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        // 入口快照：与 onDataReceived 同理，清理链可能与本调用并发
        CompletableFuture<?> currentFuture = responseFuture;
        SerialSource currentSource = serialSource;

        if (!isInUse || currentFuture == null || currentSource == null) {
            log.warn("字节监听器 #{} 在无效状态下收到错误，忽略. isInUse={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, currentFuture != null ? "exists" : "null",
                currentSource != null ? currentSource.getPortName() : "null");
            return;
        }

        log.warn("字节监听器 #{} 收到错误: {}, 串口: {}",
                instanceId, ex.getMessage(), currentSource.getPortName());

        currentFuture.completeExceptionally(ex);
        currentSource.removeDataListener(this);
    }

    @Override
    public String toString() {
        return String.format("BytePooledListener#%d[inUse=%s, port=%s]",
            instanceId, isInUse,
            serialSource != null ? serialSource.getPortName() : "null");
    }
}
