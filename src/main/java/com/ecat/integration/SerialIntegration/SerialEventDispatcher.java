package com.ecat.integration.SerialIntegration;

import java.util.concurrent.RejectedExecutionException;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * 串口入站帧事件投递器（arch-review 29 号 M3-P1 → W2-1 定稿）：IO 线程（sweeper/监听器
 * 回调）把组帧完成的入站帧投递到 {@link SerialIoPool} 的 per-port 串行视图——设备业务
 * finalize（协议解析+属性更新+complete responseFuture）不内联跑 IO 通知线程。
 *
 * <p><b>为什么归域池视图而非独立键控队列（W2-1 键对齐裁定）</b>：165500 承重
 * 不变量要求入站 finalize 与发帧写在<b>同一条 FIFO</b> 上有序（RTU 半双工总线本性）——
 * 写路径经 {@code SerialSourcePort.ioExecutor()}（=SerialIoPool.executorFor(portName)），
 * 若 finalize 走另一套独立键控队列，同口两类流量分属两个队列、物理顺序破坏。SerialIoPool
 * 的 per-port 视图本身就是以端口为键的键串行执行器（per-key FIFO+单飞 drain+有界+域池），
 * 故 finalize 与写共用同一视图。
 * {@code connId} 与写路径键的对齐：{@link SerialIoEvent#getConnId()} 即 portName（构造
 * 侧保证），与 {@code SerialSourcePort.getPortName()} 同一词汇空间。
 *
 * <p>线程契约：submit 永不阻塞调用线程（O(1) 短临界区入队）；per-port 队列拒绝/域池饱和
 * （REE）时 Transport 不重试——响应等待超时（SerialTimeoutScheduler）天然兜底终态，
 * 下一轮轮询自愈（「过期即弃」）。MDC 由 SerialIoPool 视图包装（提交时捕获、drain 执行
 * 时恢复）。
 *
 * @author coffee
 */
public final class SerialEventDispatcher {

    private static final Log log = LogFactory.getLogger(SerialEventDispatcher.class);

    private SerialEventDispatcher() {
    }

    /**
     * 投递一个入站帧事件（调用线程=sweeper/监听器通知线程，O(1) 即返）：事件体并入端口
     * 串行视图的同口 FIFO（与发帧读/写三类流量同队有序）。
     *
     * @param event        入站帧事件载荷（requestId 供日志定位；connId=端口键）
     * @param finalizeBody 事件执行体：在域池端口视图上执行的业务 finalize（complete
     *                     responseFuture 等，闭包捕获上下文）
     */
    static void submit(SerialIoEvent event, Runnable finalizeBody) {
        try {
            SerialIoPool.executorFor(event.getConnId()).execute(finalizeBody);
        } catch (RejectedExecutionException e) {
            // 域池饱和/停机（显式拒绝非静默）：按丢弃善后（WARN 记账），不重试——
            // 响应超时机制兜底终态，域池恢复后下一轮轮询自愈
            log.warn("入站帧 {} 投递遭域池拒绝（事务将由响应超时终态，不重试）: {}",
                    event.getRequestId(), e.getMessage());
        }
    }
}
