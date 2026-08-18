package com.ecat.integration.SerialIntegration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * 串口读轮询任务的调度入口（IO 线程收敛 P1 → 103000 修正：端口轮询撤出共享引擎，自持单线程）。
 *
 * <p><b>数据面 vs 任务面分离</b>（103000 归因 Mode A/B 隔离实验的直接结论）：IO 数据回调
 * （端口轮询读字节）不得与可阻塞的业务任务（无超时信号量等待 / join/get / 阻塞 send）竞争
 * 共享 worker——业务任务把引擎 worker 钉死时，轮询任务排队 → 响应字节躺在 OS 缓冲无人读 →
 * 在途事务挂到硬超时 → 端口锁/permit 长期持有 → 下个设备任务再钉死 worker，自维持闭环
 * （生产 jstack 6/6 worker 阻塞在非串口域调用 + acquire 失败 820/h）。故生产路径由本类自持的
 * 单条具名 daemon {@code serial-io-sweeper} 驱动全部端口的 {@link SerialPortPollTask}：
 * 线程账 48 口共 1 根（P1 收敛收益保留），且与业务引擎互不饿死（A/B 实验中 A 模式同阻塞
 * 负载下零失败）。
 *
 * <p>轮询任务体极轻（空闲 = 一次 volatile 读 + 一次 bytesAvailable 系统调用），单线程 25ms
 * 周期扫全部端口绰绰有余；该线程只做 IO 数据搬运，不做任何可阻塞业务（B5 契约同源）。
 *
 * <p>调度器解析顺序（{@link #delegate()}）：
 * <ol>
 *   <li>{@link #bind(ScheduledExecutorService) 显式绑定}——测试注入本地调度器的边界；
 *       生产代码不得依赖此层。</li>
 *   <li>自持单线程 daemon sweeper（"serial-io-sweeper"，懒创建、全端口共享、生命周期与
 *       进程相同）——生产路径与 standalone 端口共用同一根（轮询不感知集成归属，
 *       SerialSourcePort 的 integration 引用只用于端口表管理）。</li>
 * </ol>
 *
 * @author coffee
 */
final class SerialPollScheduler {

    private static final Log log = LogFactory.getLogger(SerialPollScheduler.class);

    /** 测试显式注入的调度器（bind/unbind）；null = 未注入，走生产 sweeper。 */
    private static volatile ScheduledExecutorService bound;

    /** 生产 sweeper：单线程 daemon serial-io-sweeper，懒创建，全部端口共享一根。 */
    private static final AtomicReference<ScheduledExecutorService> SWEEPER =
            new AtomicReference<>();

    private SerialPollScheduler() {
    }

    /**
     * 显式注入调度器（测试边界）：注入后 {@link #delegate()} 恒返回该实例，
     * 便于测试确定性地驱动/断言轮询任务的调度行为。
     *
     * @param scheduler 测试自有的调度器（生命周期由测试管理，本类不关停）
     */
    static void bind(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("bind(null) 不允许——解除绑定用 unbind()");
        }
        bound = scheduler;
    }

    /** 解除显式绑定，恢复生产 sweeper。 */
    static void unbind() {
        bound = null;
    }

    /**
     * 当前生效的调度器（解析顺序见类 Javadoc）。
     */
    static ScheduledExecutorService delegate() {
        ScheduledExecutorService explicit = bound;
        if (explicit != null) {
            return explicit;
        }
        return sweeper();
    }

    /** 当前解析来源描述（[POLL-START] 日志可观测用）。 */
    static String describe() {
        return bound != null ? "bound" : "serial-io-sweeper";
    }

    /**
     * 生产 sweeper 懒创建：单线程 daemon，线程名固定 serial-io-sweeper（jstack/日志可归属；
     * 不经 NamedThreadFactory 的 -N 后缀，单线程无需序号）。
     * 不做 MDC 包装：轮询任务是纯 IO 数据面，日志自带 port 名上下文，无 trace 语义。
     */
    private static ScheduledExecutorService sweeper() {
        ScheduledExecutorService existing = SWEEPER.get();
        if (existing != null) {
            return existing;
        }
        return SWEEPER.updateAndGet(current -> {
            if (current != null) {
                return current;
            }
            log.info("串口端口轮询启用自持单线程 serial-io-sweeper（数据面与业务引擎分离，103000）");
            return Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "serial-io-sweeper");
                t.setDaemon(true);
                return t;
            });
        });
    }
}
