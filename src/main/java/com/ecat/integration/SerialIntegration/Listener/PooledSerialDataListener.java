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
        // 入口快照：监听器业务字段在 reset() 之后、cleanup() 之前，随时可能被池释放链
        // （DefaultResponseHandlerStrategy.handleResponseInterrupt 的 whenCompleteAsync →
        // pool.release → cleanup）在另一线程并发清空——typedFuture.complete() 一经执行，
        // 监听器生命周期即移交该异步清理链，而通知线程（jSerialComm 事件线程）还要继续
        // 走完本方法。守卫校验的是快照，后续所有读写也必须用同一组引用：守卫与使用之间
        // 若改读字段，被 cleanup 置 null 即 NPE（曾被自身 catch 吞掉，表现为 WARN 风暴）。
        ResponseHandlingContext<?> currentContext = context;
        CompletableFuture<?> currentFuture = responseFuture;
        SerialSource currentSource = serialSource;
        Function<String, String> currentCheck = checkResponseFunction;

        // 安全检查：防止已被回收的监听器被调用
        if (!isInUse || currentContext == null || currentFuture == null || currentSource == null) {
            // ECAT log handles level checking automatically
            log.warn("监听器 #{} 在无效状态下收到数据，忽略. isInUse={}, context={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, currentContext != null ? "exists" : "null",
                currentFuture != null ? "exists" : "null",
                currentSource != null ? currentSource.getPortName() : "null");
            return;
        }

        try {
            String receivedData = new String(data, 0, length);
            log.trace("监听器 #{} 收到数据 [{}]: {}",
                instanceId, currentSource.getPortName(), receivedData.trim());

            // 追加接收到的数据
            currentContext.getReceiveBuffer().append(receivedData);

            // 检查响应是否完整
            String bufferContent = currentContext.getReceiveBuffer().toString();
            String checkResult = currentCheck.apply(bufferContent);

            if (checkResult != null) {
                log.debug("监听器 #{} 响应完整，移除监听器. 串口: {}, 响应长度: {}",
                    instanceId, currentSource.getPortName(), checkResult.length());

                // 设置完成标志
                currentContext.getFinishedFlag().set(true);

                // 完成Future
                @SuppressWarnings("unchecked")
                CompletableFuture<ResponseHandlingContext<?>> typedFuture =
                    (CompletableFuture<ResponseHandlingContext<?>>) currentFuture;
                // P1（29 号 M3）：IO 线程只读字节+组帧+投递——业务 finalize（complete
                // responseFuture，processResponse 续链由此在 Core Worker 上触发）经
                // Core.submit 投递，不在本（sweeper）线程内联执行。
                currentSource.submitInboundFrame(bufferContent.getBytes(), () -> typedFuture.complete(currentContext));

                // 仅在响应完整时移除监听器（保留监听器以接收分片数据的后续部分）。
                // 按监听器身份移除且幂等（CopyOnWriteArrayList.remove），与释放链
                // whenCompleteAsync 中的兜底移除互不冲突。
                currentSource.removeDataListener(this);
            }
            // 如果响应不完整，保留监听器继续接收后续数据包

        } catch (Exception e) {
            // slf4j 约定：Throwable 作最后一个参数自动绑定堆栈，不能为它再写 {} 占位符
            log.warn("监听器 #{} 处理数据时发生异常", instanceId, e);
            // 异常时也要移除监听器（currentFuture/currentSource 经守卫保证非空）
            try {
                currentFuture.completeExceptionally(e);
                currentSource.removeDataListener(this);
            } catch (Exception removeEx) {
                log.warn("监听器 #{} 移除时发生异常", instanceId, removeEx);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        // 入口快照：与 onDataReceived 同理，清理链可能与本调用并发
        CompletableFuture<?> currentFuture = responseFuture;
        SerialSource currentSource = serialSource;

        // 安全检查
        if (!isInUse || currentFuture == null || currentSource == null) {
            log.warn("监听器 #{} 在无效状态下收到错误，忽略. isInUse={}, responseFuture={}, serialSource={}",
                instanceId, isInUse, currentFuture != null ? "exists" : "null",
                currentSource != null ? currentSource.getPortName() : "null");
            return;
        }

        log.warn("监听器 #{} 收到错误: {}, 串口: {}",
                instanceId, ex.getMessage(), currentSource.getPortName());

        currentFuture.completeExceptionally(ex);
        currentSource.removeDataListener(this);
    }

    // 添加调试方法
    @Override
    public String toString() {
        return String.format("PooledListener#%d[inUse=%s, port=%s]",
            instanceId, isInUse,
            serialSource != null ? serialSource.getPortName() : "null");
    }
}
