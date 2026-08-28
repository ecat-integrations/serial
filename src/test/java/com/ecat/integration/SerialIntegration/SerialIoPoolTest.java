package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * SerialIoPool（serial 域自持 IO 池 + per-port 串行视图，29 号 v2 S1——镜像 modbus
 * ModbusIoPool 的域自持形态）契约测试：
 * <ul>
 *   <li>同口 FIFO：入站 finalize / 发帧读 / 写三类流量同口严格按提交顺序串行执行
 *       （RTU 半双工总线本性；禁退化为乱序并发写同口）；</li>
 *   <li>单飞 drain：drain 进行期间新提交不再向池重复提交 drain 任务（同口至多占一个
 *       池任务），drain 结束时队列为空才复位单飞标志；</li>
 *   <li>异口隔离：一口 drain 挂起不拖累异口（E1 语义在域池形态下保持）；</li>
 *   <li>默认池形态：daemon + 命名线程 {@code ecat-serial-io-N}；</li>
 *   <li>停机终端态：shutdown 后新提交抛 {@link RejectedExecutionException}；</li>
 *   <li>任务异常不杀 drain 循环（后续任务照常执行——视图是公共执行域，单任务异常
 *       不得钉死同口 FIFO）。</li>
 * </ul>
 *
 * <p>同步纪律：手动驱动（ManualPoolSeam 捕获 drain 提交、测试线程驱动）+ latch 有界等待，
 * 无 Thread.sleep。默认池形态用例走真实池（线程名断言需要真线程）。</p>
 */
public class SerialIoPoolTest {

    /** 捕获型手动池缝：记录 drain 提交、测试手动执行（零后台线程）。 */
    static final class ManualPoolSeam extends AbstractExecutorService implements Executor {
        final List<Runnable> drainSubmissions = new CopyOnWriteArrayList<>();
        volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("manual seam shut down");
            }
            drainSubmissions.add(command);
        }

        /** 手动驱动最近一次 drain 提交（在调用线程上执行 drain 循环）。 */
        void runLastSubmission() {
            drainSubmissions.get(drainSubmissions.size() - 1).run();
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return new ArrayList<>(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    private ManualPoolSeam seam;

    @Before
    public void setUp() {
        SerialIoPool.resetForTest();
        seam = new ManualPoolSeam();
        SerialIoPool.bindForTest(seam);
    }

    @After
    public void tearDown() {
        SerialIoPool.unbindForTest();
        SerialIoPool.resetForTest();
    }

    private static Executor view(String portName) {
        return SerialIoPool.executorFor(portName);
    }

    /** 同口三类流量（写/读/入站 finalize）按提交顺序 FIFO 串行，单飞 drain 只提交一次。 */
    @Test(timeout = 15000)
    public void samePortThreeTrafficClassesRunInFifoOrderSingleFlight() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Executor portView = view("ttyUSB-fifo");

        portView.execute(() -> order.add("write"));      // 写流量
        portView.execute(() -> order.add("read"));       // 发帧读流量
        portView.execute(() -> order.add("finalize"));   // 入站 finalize 流量

        assertEquals("三任务首提只产生一个 drain 提交（单飞）", 1, seam.drainSubmissions.size());
        seam.runLastSubmission();

        assertEquals("同口三类流量严格按提交顺序串行（FIFO）",
                java.util.Arrays.asList("write", "read", "finalize"), order);
        // drain 跑完队列空 → 单飞复位：新提交再次产生 drain 提交
        portView.execute(() -> order.add("write-2"));
        assertEquals("drain 复位后新提交重新起单飞", 2, seam.drainSubmissions.size());
        seam.runLastSubmission();
        assertEquals("复位后的任务照常执行", 4, order.size());
    }

    /** drain 进行期间（任务体阻塞）新提交不重复向池提交；释放后同 drain 循环继续消化。 */
    @Test(timeout = 15000)
    public void submissionWhileDrainingJoinsExistingDrainLoop() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        Executor portView = view("ttyUSB-singleflight");

        portView.execute(() -> {
            firstEntered.countDown();
            try {
                firstRelease.await();   // 假 IO：占住 drain（模拟写事务占口）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 测试线程驱动 drain（drain 循环阻塞在首个任务体内）
        Thread driver = new Thread(seam::runLastSubmission, "drain-driver-singleflight");
        driver.start();
        assertTrue("首个任务必须已进入执行", firstEntered.await(5, TimeUnit.SECONDS));

        portView.execute(secondDone::countDown);
        assertEquals("drain 在飞期间新提交不得重复入池（并入既有 drain）", 1, seam.drainSubmissions.size());

        firstRelease.countDown();
        assertTrue("并入既有 drain 的任务必须被同一 drain 循环消化", secondDone.await(5, TimeUnit.SECONDS));
        driver.join(5000);
    }

    /** 异口隔离：port-A 的 drain 挂起不阻止 port-B 的 drain 提交与执行（E1 语义）。 */
    @Test(timeout = 15000)
    public void blockedPortDrainDoesNotStallOtherPort() throws Exception {
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);

        view("ttyUSB-A-iso").execute(() -> {
            aEntered.countDown();
            try {
                aRelease.await();   // port-A drain 挂起（模拟 E1 中超时设备占住执行域）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread driverA = new Thread(seam::runLastSubmission, "drain-driver-A");
        driverA.start();
        assertTrue(aEntered.await(5, TimeUnit.SECONDS));

        view("ttyUSB-B-iso").execute(bDone::countDown);
        assertEquals("port-B 必须获得独立的 drain 提交（异口不共享队列）", 2, seam.drainSubmissions.size());
        seam.runLastSubmission();    // 驱动 port-B 的 drain（port-A 仍挂起）
        assertTrue("port-B 不得被 port-A 挂起拖累", bDone.await(5, TimeUnit.SECONDS));

        aRelease.countDown();
        driverA.join(5000);
    }

    /** 任务异常不杀 drain 循环：异常任务之后的同口任务照常执行（视图不得被钉死）。 */
    @Test(timeout = 15000)
    public void taskExceptionDoesNotKillDrainLoop() throws Exception {
        CountDownLatch survivorDone = new CountDownLatch(1);
        Executor portView = view("ttyUSB-ex");
        portView.execute(() -> {
            throw new IllegalStateException("boom: 模拟设备任务异常");
        });
        portView.execute(survivorDone::countDown);

        seam.runLastSubmission();   // 同一 drain 循环：吞掉异常任务后必须继续
        assertTrue("异常任务后的同口任务必须照常执行", survivorDone.await(5, TimeUnit.SECONDS));
    }

    /** 池饱和（drain 提交被拒）：提交方收到 REE（AbortPolicy 语义），同口队列被清空不残留。 */
    @Test(timeout = 15000)
    public void poolSaturationRejectsSubmitterAndClearsPortQueue() throws Exception {
        Executor portView = view("ttyUSB-sat");
        seam.shutdownNow();   // 手动缝进入停机 → drain 提交必拒（模拟池饱和 AbortPolicy）
        try {
            portView.execute(() -> { });
            fail("池饱和时提交方必须收到 RejectedExecutionException（过期即弃，显式不静默）");
        } catch (RejectedExecutionException expected) { }
        // 复位缝后同口视图可继续工作（单飞标志已回卷，无残留死锁）
        seam.shutdown = false;
        CountDownLatch recovered = new CountDownLatch(1);
        portView.execute(recovered::countDown);
        seam.runLastSubmission();
        assertTrue("饱和拒绝后同口视图必须可自愈（下一提交重新起单飞）", recovered.await(5, TimeUnit.SECONDS));
    }

    /** 默认池形态：daemon + ecat-serial-io-N 命名线程（真实池，线程断言需要真线程）。 */
    @Test(timeout = 15000)
    public void defaultPoolIsDaemonNamedEcatSerialIo() throws Exception {
        SerialIoPool.unbindForTest();   // 走生产默认池
        final CountDownLatch fired = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> threadName =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Boolean> daemon =
                new java.util.concurrent.atomic.AtomicReference<>();
        view("ttyUSB-default-form").execute(() -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            fired.countDown();
        });

        assertTrue("默认池任务必须在观察窗内执行", fired.await(5, TimeUnit.SECONDS));
        assertTrue("线程名须为 ecat-serial-io-N（实际 " + threadName.get() + "）",
                threadName.get().matches("ecat-serial-io-\\d+"));
        assertEquals("域 IO 线程必须 daemon（不阻 JVM 退出）", Boolean.TRUE, daemon.get());

        SerialIoPool.shutdown();
        try {
            view("ttyUSB-default-form").execute(() -> { });
            fail("域池停机后新提交必须 RejectedExecutionException（终端态）");
        } catch (RejectedExecutionException expected) { }
    }

    /** 可观测出口：describe() 标识域池与视图维度（替换引擎车道账目）。 */
    @Test
    public void describeExposesPoolDimension() {
        view("ttyUSB-desc");
        String status = SerialIoPool.describe();
        assertTrue("describe 应标识 serial 域 IO 池: " + status, status.contains("ecat-serial-io"));
        assertTrue("describe 应含视图（端口）维度", status.contains("views"));
    }

    /** 同口视图幂等：同端口名取同实例、异端口名异实例（per-port 串行坐标唯一）。 */
    @Test
    public void portViewIsIdempotentPerPortName() {
        Executor a1 = view("ttyUSB-view");
        Executor a2 = view("ttyUSB-view");
        Executor b = view("ttyUSB-view-b");
        assertSamePort(a1, a2);
        assertTrue("异端口视图必须不同实例（异口并行、同口串行的分道坐标）", a1 != b);
    }

    private static void assertSamePort(Object expected, Object actual) {
        assertNotNull(expected);
        assertEquals("同端口视图必须幂等同实例", expected, actual);
    }

    /** 并行不倒退：异口任务在真实池上并行执行（同口串行不等于全域串行）。 */
    @Test(timeout = 15000)
    public void differentPortsRunInParallelOnRealPool() throws Exception {
        SerialIoPool.unbindForTest();   // 真实默认池
        CountDownLatch bothIn = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        view("ttyUSB-par-A").execute(() -> awaitLatch(bothIn, release));
        view("ttyUSB-par-B").execute(() -> awaitLatch(bothIn, release));

        // bothIn=2 只在两口任务同时驻留时计数满——若域池把异口也串行化，第二个任务
        // 排在第一个 release 之后，latch 必超时（并行性由此 latch 确定性证明）
        assertTrue("两个异口任务必须同时进入（并行证据；若串行化则本断言超时红）",
                bothIn.await(5, TimeUnit.SECONDS));
        release.countDown();
    }

    private static void awaitLatch(CountDownLatch bothIn, CountDownLatch release) {
        bothIn.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
