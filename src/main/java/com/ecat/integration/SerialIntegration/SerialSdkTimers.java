package com.ecat.integration.SerialIntegration;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Task.runner.PeriodicRunner;

/**
 * serial 域 SDK 的定时执行器所有权与测试缝（29 号 v2 S1：serial 域脱离 core 调度引擎，
 * 定时/执行语义归传输 SDK；镜像 http S0 的 HttpSdkTimers 形态——域自持池 + 缝，
 * 周期链骨架/MDC 原语消费 core 库 {@link PeriodicRunner}）。
 *
 * <p><b>为什么自持</b>：SDK 的周期语义（完成点重排/名义网格/过期即弃）全部内化在
 * {@link SerialPolling} 的轮体与 {@link SerialPollSchedule} 网格策略里，对定时器的
 * 全部需求收敛为一种原语——「MDC 包装的到点单发」（core PeriodicRunner 实现）。本类
 * 持有唯一默认池（daemon、命名 {@code ecat-serial-sched-N}），池尺寸按 serial 域现实
 * 负载定 2：域内百口量级（live 观测 ~161 口）轮询的发起段都是 µs 级提交（锁忙有界
 * 等待已移交 {@link SerialIoPool} 旁池线程 + 事务 CF 接线，真实串口 IO 同走
 * {@link SerialIoPool}），单发消费者还有事务硬超时执法
 * /响应超时标记/命令间 {@code delay()} 补全（同为 µs 级）——单条 STPE 线程即可承载
 * 每秒数千次 µs 单发；取 2 条吸收多口相位重合的到拍尖峰，且单条被慢提交钉死时
 * 超时执法/周期链仍在另一条上准点发射（超时执法被钉死=硬超时失效=B5 幽灵锁回归，
 * 故冗余 1 条是安全语义非余量浪费）。</p>
 *
 * <p><b>MDC</b>：单发的提交时捕获（coordinate）、到拍恢复、无 traceId 补生成，由
 * {@link PeriodicRunner#fireAfter(Runnable, long)} 内置（core TraceContext 同一实现）；
 * 周期链的逐轮包装（提交 coordinate+每轮新 traceId）由 PeriodicChain 内置。</p>
 *
 * <p><b>生命周期</b>：唯一停机入口 {@link #shutdown()}（幂等、终端态，不自动复活），
 * 由 serial 集成 {@code onRelease} 调用（消费集成先于依赖集成释放，链路已先经
 * RemovalHost 收口）。停机后新提交抛 {@link RejectedExecutionException}——严格模式，
 * 不静默吞。</p>
 *
 * <p><b>测试缝</b>：{@code bindForTest}/{@code unbindForTest} 注入替身（包内可见，
 * 生产禁用）；{@code resetForTest} 仅供测试独占默认池。缝类型窄化为
 * {@link ShotScheduler}——SDK 只消费单发形态，不暴露整个执行器面；{@link #runner()}
 * 在当前缝上取 core PeriodicRunner，bind 替身后链路整体走替身。</p>
 */
public final class SerialSdkTimers {

    /**
     * 单发提交缝：到点执行一次（命令已含 MDC 包装），返回可取消句柄。
     * 窄于 {@link ScheduledExecutorService}：SDK 周期链自排（每拍一单发），
     * 不使用执行器原生周期形态，缝面即实际消费面。
     */
    interface ShotScheduler {

        /**
         * @param command      已包装命令（MDC 语义在包装层完成）
         * @param delayMillis  延迟毫秒（0=立即）
         * @return 可取消句柄（链路 cancel 撤销待发拍）
         */
        ScheduledFuture<?> fireAfter(Runnable command, long delayMillis);
    }

    /** 默认池尺寸（线程预算论证见类注释）。 */
    private static final int POOL_SIZE = 2;

    /** 懒持有的默认池：类加载不建线程，首个真实单发才起步 worker。 */
    private static volatile ScheduledThreadPoolExecutor pool;

    /** 测试注入的缝；null = 走默认池。仅测试代码可写。 */
    private static volatile ShotScheduler bound;

    /** 当前缝上的 core 周期链工具（lambda 逐调用解析 seam，bind 替身即时生效）。 */
    private static final PeriodicRunner RUNNER = PeriodicRunner.on(
            (command, delayMillis) -> seam().fireAfter(command, delayMillis));

    private SerialSdkTimers() {
    }

    /** 当前缝上的 PeriodicRunner：周期链与 MDC 单发原语（SerialPolling 消费面）。 */
    static PeriodicRunner runner() {
        return RUNNER;
    }

    /**
     * 提交一个 MDC 包装单发（提交时捕获上下文、到拍恢复、无 traceId 补生成）。
     * serial 域内跨包消费面（SerialPolling 命令间 delay / SerialTimeoutScheduler 超时执法）。
     *
     * @throws RejectedExecutionException 默认池已 shutdown（终端态）
     */
    public static ScheduledFuture<?> fireAfter(Runnable command, long delayMillis) {
        return RUNNER.fireAfter(command, delayMillis);
    }

    /** 停机钩子（幂等）：撤销全部待发单发并中断在飞执行。终端态——不自动复活。 */
    public static void shutdown() {
        ScheduledThreadPoolExecutor current = pool;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 真 {@link ScheduledExecutorService} 到缝的适配器（联调测试自备真池用）。 */
    static ShotScheduler forScheduledExecutor(ScheduledExecutorService executor) {
        return (command, delayMillis) -> executor.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
    }

    // ==================== 以下均为测试缝（生产禁用） ====================

    /** 注入替身缝（仅测试）。 */
    static void bindForTest(ShotScheduler seam) {
        if (seam == null) {
            throw new IllegalArgumentException("bindForTest(null) 不允许——解除用 unbindForTest()");
        }
        bound = seam;
    }

    /** 解除注入，恢复默认池。 */
    static void unbindForTest() {
        bound = null;
    }

    /**
     * 重建默认池（仅测试基建：隔离其他测试类经 onRelease 关池的顺序影响；
     * serial 域内跨包测试消费，故 public——生产禁用）。
     */
    public static void resetForTest() {
        ScheduledThreadPoolExecutor current = pool;
        if (current != null) {
            current.shutdownNow();
        }
        pool = null;
    }

    private static ShotScheduler seam() {
        ShotScheduler testSeam = bound;
        if (testSeam != null) {
            return testSeam;
        }
        return defaultSeam();
    }

    private static ShotScheduler defaultSeam() {
        ScheduledThreadPoolExecutor current = pool;
        if (current == null) {
            synchronized (SerialSdkTimers.class) {
                if (pool == null) {
                    ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                            POOL_SIZE, new NamedThreadFactory("ecat-serial-sched", true));
                    // 链路 cancel/超时撤销高频（每轮一撤）：取消即出队，防队列滞留
                    created.setRemoveOnCancelPolicy(true);
                    pool = created;
                }
                current = pool;
            }
        }
        return forScheduledExecutor(current);
    }
}
