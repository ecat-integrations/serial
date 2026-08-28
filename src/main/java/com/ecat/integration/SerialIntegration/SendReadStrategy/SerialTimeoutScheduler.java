package com.ecat.integration.SerialIntegration.SendReadStrategy;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.integration.SerialIntegration.SerialSdkTimers;

/**
 * 串口超时调度入口（29 号 v2 S1 定稿：serial 域自持定时）：超时任务统一由域定时器
 * {@link SerialSdkTimers}（daemon 池 ecat-serial-sched-N，MDC 包装单发）承载，
 * serial 域不再依赖 core 调度引擎（原 B1 引擎表轮路径与 SdkSchedulerResolver 一并退役）。
 *
 * <p>调度器解析顺序（{@link #schedule(Runnable, long, TimeUnit)}）：
 * <ol>
 *   <li>{@link #bind(ScheduledExecutorService) 显式绑定}——测试注入本地调度器的边界；
 *       生产代码不得依赖此层。</li>
 *   <li>域定时器 {@link SerialSdkTimers#fireAfter}（生产路径；MDC 提交时捕获、到拍恢复、
 *       无 traceId 补生成）。</li>
 * </ol>
 *
 * <p>超时行为契约：
 * <ul>
 *   <li>触发时间：STPE 毫秒精度（原引擎 tick 100ms 取整粒度随引擎退役消失——超时执法
 *       更准点，不晚于声明的 deadline）；</li>
 *   <li>隔离粒度：域定时器与业务周期链同池不同任务（任务体均为 µs 级 complete/remove，
 *       互不饥饿；域池尺寸论证见 {@link SerialSdkTimers}）；</li>
 *   <li>取消：{@link ScheduledFuture#cancel(boolean)} 语义不变（STPE 取消即出队）。</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialTimeoutScheduler {

    /** 测试显式注入的调度器（bind/unbind）；null = 未注入，走域定时器。 */
    private static volatile ScheduledExecutorService bound;

    private SerialTimeoutScheduler() {
    }

    /**
     * 显式注入调度器（测试边界）：注入后 {@link #schedule(Runnable, long, TimeUnit)}
     * 恒经该实例，便于测试确定性地断言「超时确实经由此调度器」。
     *
     * @param scheduler 测试自有的调度器（生命周期由测试管理，本类不关停）
     */
    public static void bind(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        bound = scheduler;
    }

    /** 解除显式绑定，恢复域定时器（生产路径）。 */
    public static void unbind() {
        bound = null;
    }

    /**
     * 调度一个超时任务（测试注入优先，否则域定时器 MDC 包装单发）。
     *
     * @param command 要执行的任务
     * @param delay   延迟时间
     * @param unit    时间单位
     * @return ScheduledFuture 对象，可用于取消任务
     */
    public static ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ScheduledExecutorService explicit = bound;
        if (explicit != null) {
            return explicit.schedule(command, delay, unit);
        }
        long delayMillis = unit.toMillis(delay);
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delay 换算为毫秒后须 >= 0（溢出）, delay=" + delay + " " + unit);
        }
        return SerialSdkTimers.fireAfter(command, delayMillis);
    }
}
