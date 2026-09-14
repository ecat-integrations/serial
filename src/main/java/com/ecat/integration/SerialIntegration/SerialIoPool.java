package com.ecat.integration.SerialIntegration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Mdc.MdcExecutorService;

/**
 * serial 域自持 IO 池 + per-port 串行视图（29 号 v2 S1——镜像 modbus ModbusIoPool 的
 * 域自持形态，替代引擎车道视图 SerialIoLanes：jSerialComm 阻塞读写全部落本域池，
 * SDK 的 IO 执行不再与任何引擎/业务任务共享队列）。
 *
 * <p><b>结构（modbus 单飞 drain 的 serial 移植）</b>：定容 daemon 线程池
 * （{@code ecat-serial-io-N}）+ per-port 队列单飞 drain——{@link #executorFor(String)}
 * 返回端口的 {@link ExecutorService} 视图，提交进端口 FIFO 队列；首个提交向池提交一个
 * drain 任务，drain 循环在池线程上逐个消化本口队列直到空（期间新提交并入既有 drain，
 * 不再占新池任务），由此保证：
 * <ul>
 *   <li><b>同口 FIFO</b>：入站 finalize × 发帧读 × 写三类流量同口严格按提交顺序串行
 *       （RTU 半双工总线本性，禁乱序并发写同口——165500 的承重顺序）；</li>
 *   <li><b>异口并行</b>：每口独立队列/独立 drain，一口挂起不拖累异口（E1 语义保持）；</li>
 *   <li><b>165500 不变量</b>：口内任务只有有界 IO（非阻塞写/缓冲读/毫秒级 finalize），
 *       无任何「等待侧占口」形态驻留（165500 事故形态=写事务的 permit 有界等待发生在
 *       车道 worker 上饿死同口 finalize）——FIFO 公平使 finalize 的等待上界
 *       =入队时刻排在前面的任务数，与其身后持续到来的写压测无关。</li>
 * </ul>
 *
 * <p><b>071900 队头自锁的结构性根除</b>：写闸任务体（引擎车道/业务线程）与写 IO
 * （本域池 per-port 视图）分属两个队列，跨执行域 join 天然完成——旧「当前线程即本口
 * 车道 worker 则直发」的逃逸口及其引擎检测已删除（bugs/fixed/bug-record-20260826-071900，
 * 见 SerialSourcePort.asyncSendData javadoc）。</p>
 *
 * <p><b>尺寸论证</b>：池线程 {@link #POOL_SIZE}=16（modbus 域同尺寸已 live 验证）。
 * 单飞 drain 使每口至多占一个池任务，故池需求上界=同时活跃口数（live 观测 ~161 口）；
 * 口内任务 µs~ms 级（非阻塞写/缓冲交换），稳态并发 drain 远低于上界。排队容量
 * {@link #QUEUE_CAPACITY}=192：每口至多一个 drain 排队 → 队列上界=口数（161），
 * 192 覆盖全口冷启动齐发突发不拒绝；真饱和（16 线程全忙且队满）按 AbortPolicy 抛
 * {@link RejectedExecutionException}——提交方收「过期即弃」显式信号（轮询本周期放弃
 * 下周期再试），同口队列被清空回卷单飞标志（视图可自愈，不残留死锁），不用
 * CallerRunsPolicy（把阻塞弹回发起线程=重新钉死业务执行域）。</p>
 *
 * <p><b>生命周期</b>：唯一停机入口 {@link #shutdown()}（幂等、终端态），由 serial
 * 集成 {@code onRelease} 调用；停机后新提交抛 REE。无集成上下文（单测/独立运行）
 * 由首个取用方触发惰性建池。MDC 经 {@link MdcExecutorService} 提交时捕获、drain
 * 执行时逐任务恢复（与既有车道同权）。</p>
 */
public final class SerialIoPool {

    private static final Log log = LogFactory.getLogger(SerialIoPool.class);

    /** 池线程数（尺寸论证见类注释）。 */
    private static final int POOL_SIZE = 16;

    /** drain 排队容量：覆盖全口冷启动齐发突发（每口至多 1 个 drain 排队）。 */
    private static final int QUEUE_CAPACITY = 192;

    /** 当前域池（volatile 无锁读快路径；建池/停机在类锁内写）。 */
    private static volatile ThreadPoolExecutor pool;

    /** 停机终端态标志：置位后不再惰性重建（严格模式，停机不自动复活）。 */
    private static volatile boolean terminated;

    /** per-port 视图缓存（端口对象生命周期内幂等；值为 MDC 包装后的视图）。 */
    private static final ConcurrentHashMap<String, ExecutorService> PORT_VIEWS = new ConcurrentHashMap<>();

    /** 测试注入的池缝（execute 面——实际消费面）；null = 走默认池。仅测试代码可写。 */
    private static volatile Executor boundPool;

    private SerialIoPool() {
    }

    /**
     * 端口的 IO 串行视图（同口幂等同实例）：提交按 FIFO 串行执行，异口互不影响。
     * SerialSourcePort.ioExecutor 的底层实现（公共出口 SerialSource.getIoExecutor）。
     * MDC 提交时捕获、drain 执行时恢复（MdcExecutorService 包装）。
     *
     * @param portName 端口名（SerialInfo.portName）
     * @return 端口串行视图（ExecutorService 面：execute/submit/whenCompleteAsync 通用）
     */
    public static ExecutorService executorFor(String portName) {
        return PORT_VIEWS.computeIfAbsent(portName,
                name -> MdcExecutorService.wrap(new PortSerialExecutor(name)));
    }

    /**
     * 域池直接提交面（绕过 per-port 串行视图，20260913-073600 方案 c）：供「轮询锁忙
     * 有界等待」这类需要独立占线程 park 的任务卸载——<b>不得</b>走
     * {@link #executorFor(String)} 的口内串行车道：车道是 FIFO 单飞，等待任务在其上
     * park 会钉死同口全部 IO（发帧/读/finalize 排在等待之后），且后来的等待者没机会
     * 入锁等待队列（FIFO 公平被车道串行化破坏）。与 per-port 视图同一底层池、同一
     * 饱和/停机语义（饱和抛 REE=调用方按弃轮记账，停机抛 REE 终态拒绝）。包内可见：
     * 消费面是 {@code SerialSourcePort.acquirePollingBounded}（MDC 由其任务体内自带）。
     */
    static Executor domainExecutor() {
        return drainTarget();
    }

    /**
     * 停机钩子（幂等、终端态）：serial 集成 onRelease 调用。shutdownNow 中断在飞
     * 阻塞 IO（jSerialComm 阻塞点随集成释放整体拆除），此后新提交抛 REE。
     */
    public static synchronized void shutdown() {
        terminated = true;
        ThreadPoolExecutor current = pool;
        if (current != null && !current.isShutdown()) {
            current.shutdownNow();
            log.info("serial 域 IO 池已停机（ecat-serial-io-0.." + (current.getMaximumPoolSize() - 1) + "）");
        }
        pool = null;
        PORT_VIEWS.clear();
    }

    /**
     * 域池可观测出口（替换引擎车道账目透传）：标识池形态与视图数。
     */
    public static String describe() {
        ThreadPoolExecutor current = pool;
        return "SerialIoPool[ecat-serial-io-" + POOL_SIZE + "threads, views=" + PORT_VIEWS.size()
                + ", layer=" + (boundPool != null ? "test-bound" : current != null ? "default" : "lazy") + "]";
    }

    // ==================== 以下均为测试缝（生产禁用） ====================

    /** 注入池替身（仅测试：捕获 drain 提交、手动驱动；类型=execute 面）。 */
    static void bindForTest(Executor poolSeam) {
        if (poolSeam == null) {
            throw new IllegalArgumentException("bindForTest(null) 不允许——解除用 unbindForTest()");
        }
        boundPool = poolSeam;
    }

    /** 解除注入，恢复默认池。 */
    static void unbindForTest() {
        boundPool = null;
    }

    /** 重建默认池（仅测试基建：隔离其他测试类经 onRelease 关池的顺序影响）。 */
    static void resetForTest() {
        ThreadPoolExecutor current = pool;
        if (current != null) {
            current.shutdownNow();
        }
        pool = null;
        terminated = false;
        PORT_VIEWS.clear();
    }

    /** drain 提交目标：测试缝优先，否则默认池（惰性建池；停机终端态显式拒绝）。 */
    private static Executor drainTarget() {
        Executor seamPool = boundPool;
        if (seamPool != null) {
            return seamPool;
        }
        if (terminated) {
            throw new RejectedExecutionException("serial IO pool terminated (onRelease terminal state)");
        }
        ThreadPoolExecutor current = pool;
        if (current == null || current.isShutdown()) {
            synchronized (SerialIoPool.class) {
                if (terminated) {
                    throw new RejectedExecutionException("serial IO pool terminated (onRelease terminal state)");
                }
                if (pool == null || pool.isShutdown()) {
                    pool = newPool();
                    log.info("serial 域 IO 池就绪：ecat-serial-io-0.." + (POOL_SIZE - 1)
                            + "（" + POOL_SIZE + " daemon 线程 + 排队容量 " + QUEUE_CAPACITY + "）");
                }
                current = pool;
            }
        }
        return current;
    }

    /** 建池：定容 N 线程 + 有界排队 + AbortPolicy（队满即拒=过期即弃）+ daemon 命名线程。 */
    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
                new NamedThreadFactory("ecat-serial-io", true),
                new AbortAndLogPolicy());
    }

    /** AbortPolicy + 告警日志（拒绝可观测，禁静默）。 */
    private static final class AbortAndLogPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("serial 域 IO 池饱和（16 线程全忙且队满），drain 提交被拒（过期即弃，下周期自愈）");
            throw new RejectedExecutionException("serial IO pool saturated (drain submission rejected)");
        }
    }

    /**
     * per-port 串行视图：端口 FIFO 队列 + 单飞 drain（modbus ModbusSource.dispatchIo
     * 同型结构的 executor 面封装）。MDC 由外层 {@link MdcExecutorService} 包装
     * （提交时捕获包进任务，drain 执行时恢复）。
     */
    private static final class PortSerialExecutor extends AbstractPoolViewExecutor {

        private final String portName;
        /** 端口 FIFO（drain 单线程消费 + 提交多线程生产；draining 标志与之同锁）。 */
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        /** true=已有 drain 任务在池上消费本口队列（提交侧据此并入而非重复提交）。 */
        private boolean draining;

        PortSerialExecutor(String portName) {
            this.portName = portName;
        }

        void executeSerial(Runnable command) {
            boolean submitDrain = false;
            synchronized (queue) {
                if (shutdown) {
                    throw new RejectedExecutionException("serial io view shut down: " + portName);
                }
                queue.addLast(command);
                if (!draining) {
                    draining = true;
                    submitDrain = true;
                }
            }
            if (submitDrain) {
                try {
                    drainTarget().execute(this::drainLoop);
                } catch (RejectedExecutionException poolSaturated) {
                    failAllPending(poolSaturated);
                }
            }
        }

        /**
         * drain 循环（池线程）：逐个消化本口队列直到空。单任务异常不得杀循环
         * （视图是公共执行域，异常任务如实记日志后继续——后续任务的 FIFO 不被钉死）。
         */
        private void drainLoop() {
            for (;;) {
                Runnable task;
                synchronized (queue) {
                    task = queue.pollFirst();
                    if (task == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("[serial-io] port={} 口内任务异常（drain 续跑，不钉死同口 FIFO）",
                            portName, t);
                }
            }
        }

        /**
         * 池饱和善后（镜像 modbus failAllPending）：清空本口队列回卷单飞标志（下一提交
         * 重新起 drain，视图自愈），丢弃任务如实记账——响应超时机制天然兜底终态
         * （INBOUND 丢弃不重试同款语义），并向首提交方重抛 REE（显式拒绝非静默）。
         */
        private void failAllPending(RejectedExecutionException cause) {
            List<Runnable> dropped = new ArrayList<>();
            synchronized (queue) {
                Runnable task;
                while ((task = queue.pollFirst()) != null) {
                    dropped.add(task);
                }
                draining = false;
            }
            log.warn("[serial-io] port={}, 池饱和丢弃 {} 个口内任务（过期即弃，响应超时兜底）",
                    portName, dropped.size());
            throw cause;
        }

        String describeTarget() {
            return "serial-io:" + portName;
        }
    }

    /**
     * 端口视图的 ExecutorService 骨架（生命周期语义：池/视图停机后拒绝新提交；
     * 视图不拥有线程，shutdown 不中断在飞任务——在飞 IO 由域池停机统一中断）。
     */
    private abstract static class AbstractPoolViewExecutor
            extends AbstractExecutorService {

        /** 视图停机标志（域池停机/池饱和善后续置；置位后 execute 拒绝）。 */
        volatile boolean shutdown;

        /** 子类实现：入队并按需起单飞 drain（停机时抛 REE）。 */
        abstract void executeSerial(Runnable command);

        /** 日志/异常定位用端口坐标。 */
        abstract String describeTarget();

        @Override
        public void execute(Runnable command) {
            if (command == null) {
                throw new NullPointerException("command 不能为 null");
            }
            executeSerial(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            // 诚实语义：视图不拥有线程，无「线程终止」事件可等；已停机即视为终态
            return shutdown;
        }
    }
}
