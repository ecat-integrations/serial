package com.ecat.integration.SerialIntegration.SendReadStrategy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.ecat.core.EcatCore;
import com.ecat.core.Task.TaskManager;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Mdc.MdcScheduledExecutorService;

/**
 * 串口超时调度入口（B3 合并后）：超时任务统一交给共享调度器承载，serial 集成不再自建
 * 专用超时调度线程（旧实现每端口一根 {@code SerialTimeoutScheduler-<port>}，实测 ~25 根）。
 *
 * <p>调度器解析顺序（{@link #delegate()}）：
 * <ol>
 *   <li>{@link #bind(ScheduledExecutorService) 显式绑定}——测试注入本地调度器的边界；
 *       生产代码不得依赖此层。</li>
 *   <li>运行中的 ECAT 平台（{@code EcatCore.getInstance()} 非空）→ B1 调度引擎
 *       {@link TaskManager#getMdcScheduledExecutorService()}（表轮+车道；生产路径）。</li>
 *   <li>本地兜底单线程 daemon（"serial-timeout-local"）——无 core 上下文的单测/独立运行
 *       （serial 自身与下游设备集成的单测都不启 core）。此为显式测试边界，非生产兜底：
 *       生产中集成由 EcatCore 加载，core 实例必先于任何设备代码就绪。</li>
 * </ol>
 *
 * <p>超时行为契约（与旧每端口 STPE 的差异，均继承自 B1 平台契约——141 个既有
 * {@code getScheduledExecutor()} 调用点同款）：
 * <ul>
 *   <li>触发时间：deadline 向上取整到引擎 tick（默认 100ms），即 {@code [timeout, timeout+tick)}
 *       内触发；本地兜底/测试注入仍为 STPE 毫秒精度。</li>
 *   <li>隔离粒度：旧为每端口一根线程；新为每集成坐标一条逻辑车道（引擎车道），超时任务体
 *       为微秒级（complete future + remove listener），无饥饿之虞；一次性任务熔断恒放行。</li>
 *   <li>取消：{@link ScheduledFuture#cancel(boolean)} 语义不变（引擎侧到点扫描惰性丢弃已取消任务）。</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialTimeoutScheduler {

    private static final Log log = LogFactory.getLogger(SerialTimeoutScheduler.class);

    /** 测试显式注入的调度器（bind/unbind）；null = 未注入，走默认解析。 */
    private static volatile ScheduledExecutorService bound;

    /** 无 core 上下文时的本地兜底（懒创建，单线程 daemon，不阻止 JVM 退出）。 */
    private static final AtomicReference<ScheduledExecutorService> LOCAL =
            new AtomicReference<>();

    private SerialTimeoutScheduler() {
    }

    /**
     * 显式注入调度器（测试边界）：注入后 {@link #delegate()} 恒返回该实例，
     * 便于测试确定性地断言「超时确实经由此调度器」。
     *
     * @param scheduler 测试自有的调度器（生命周期由测试管理，本类不关停）
     */
    public static void bind(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        bound = scheduler;
    }

    /** 解除显式绑定，恢复默认解析（core 引擎 → 本地兜底）。 */
    public static void unbind() {
        bound = null;
    }

    /**
     * 当前生效的调度器（解析顺序见类 Javadoc）。
     */
    public static ScheduledExecutorService delegate() {
        ScheduledExecutorService explicit = bound;
        if (explicit != null) {
            return explicit;
        }
        EcatCore core = EcatCore.getInstance();
        if (core != null) {
            return core.getTaskManager().getMdcScheduledExecutorService();
        }
        return localFallback();
    }

    /** 本地兜底懒创建：单线程 daemon，MDC 包装（与旧每端口池一致）。 */
    private static ScheduledExecutorService localFallback() {
        ScheduledExecutorService existing = LOCAL.get();
        if (existing != null) {
            return existing;
        }
        return LOCAL.updateAndGet(current -> {
            if (current != null) {
                return current;
            }
            log.info("无 ECAT core 上下文（单测/独立运行），使用本地超时调度线程 serial-timeout-local");
            return MdcScheduledExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "serial-timeout-local");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }));
        });
    }

    /**
     * 调度一个超时任务（委托共享调度器；车道按提交方集成坐标划分，与端口无关）。
     *
     * @param command 要执行的任务
     * @param delay   延迟时间
     * @param unit    时间单位
     * @return ScheduledFuture 对象，可用于取消任务
     */
    public static ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate().schedule(command, delay, unit);
    }
}
