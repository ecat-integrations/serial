package com.ecat.integration.SerialIntegration;

/**
 * 轮询任务句柄（{@link SerialPolling#start()} 返回值）：对齐存量设备仓
 * {@code scheduledFuture.cancel(false)} 的生命周期语义——cancel 不打断在飞轮次
 * （不打断进行中的事务），取消后续排程；设备 stop() 释放资源时统一 cancel。
 *
 * <p>托管兜底：{@link SerialPolling#start()} 内部经 {@code host.onRemove(handle::cancel)}
 * 把句柄注册到宿主移除生命周期（18 号设计 §3.3），设备 {@code cancelManagedTasks()}
 * 的 LIFO sweep 会执行 cancel——幂等不双杀（M2/R6-lite 契约），与设备自持 handle 并存。
 *
 * <p>当前为 serial 域句柄（W2a 随 SerialPolling 落地）；modbus/tcp/http 域 SDK
 * （17 号 §2 L2 五域）后续批次若需同形句柄，按 LockBusySkippedException 同路径
 * 下沉 core 复用，不另造平行词汇。
 *
 * @author coffee
 */
public interface PollingHandle {

    /**
     * 取消轮询（幂等）：不打断在飞轮次，取消后续排程——与存量
     * {@code scheduledFuture.cancel(false)} 语义一致。
     */
    void cancel();

    /**
     * 轮询是否仍在调度（未被 cancel）。
     *
     * @return true = 已 start 且未 cancel；cancel 后恒 false
     */
    boolean isRunning();
}
