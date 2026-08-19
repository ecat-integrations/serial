package com.ecat.integration.SerialIntegration;

import java.util.concurrent.ExecutorService;
import com.ecat.core.Task.GuardedExecutor;

/**
 * Serial 集成模块统一的异步执行器
 * 为所有串口相关的异步操作提供统一的执行通道
 *
 * <p>执行通道为 GuardedExecutor 硬超时看门狗视图（同 gate FIFO 串行 + 超时执法 + 有账），
 * MDC/traceId 上下文随提交捕获、在 worker 内恢复，日志正确路由到对应集成。
 * 池由 GuardedExecutor 共享持有（daemon 线程，随 JVM 退出），本类不再自持线程池，
 * 状态查询透传 GuardedExecutor 全局账目（completed/timedOut/rejected/running/activeGates）。
 *
 * @author coffee
 */
public class SerialAsyncExecutor {

    /**
     * 统一串口异步操作执行通道（arch-review 27 号组件示范接入）：看门狗 guarded 视图。
     * 提交到 GuardedExecutor 共享小池（gate=serial-async 同 gate FIFO 串行），超时由硬超时
     * 看门狗执法（默认 60s，可配 ecat.guarded.timeout-ms）；MDC/traceId 由 GuardedExecutor
     * 提交时捕获、worker 内恢复，等价原 MdcExecutorService.wrap 语义。
     * 不可中断任务超时后占槽至自然结束是有意的诚实边界（隔离上限=看门狗池大小）。
     */
    private static final ExecutorService EXECUTOR = GuardedExecutor.guardedExecutorFor(
        "serial-async", GuardedExecutor.defaultTimeoutMs());

    /**
     * 获取统一的异步执行器（guarded 视图，已含 MDC 传播）
     * @return ExecutorService 线程池
     */
    public static ExecutorService getExecutor() {
        return EXECUTOR;
    }

    /**
     * 获取执行通道状态信息（GuardedExecutor 共享池的全局账目，非本 gate 独立计数）。
     * 原 ThreadPoolExecutor 版本在切换 guarded 视图后已成零任务死池、指标恒零误导排障，
     * 已删除；此处透传真实账目保持诊断出口可用。
     * @return 状态字符串
     */
    public static String getStatus() {
        return "SerialAsyncExecutor[guarded, " + GuardedExecutor.getStats() + "]";
    }
}
