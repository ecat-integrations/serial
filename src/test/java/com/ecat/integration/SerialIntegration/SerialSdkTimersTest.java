package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Mdc.MdcContext;
import com.ecat.core.Utils.Mdc.TraceContext;

/**
 * SerialSdkTimers（serial 域自持定时原语，29 号 v2 S1——镜像 http S0 的 HttpSdkTimers 形态）
 * 契约测试：
 * <ul>
 *   <li>MDC 传播最小集：提交时捕获（coordinate）、到拍恢复、无 traceId 则生成——
 *       core PeriodicRunner.fireAfter 内置语义经域池生效；</li>
 *   <li>默认池形态：daemon + 命名线程 {@code ecat-serial-sched-N}（线程预算可观测面）；</li>
 *   <li>停机钩子：{@code shutdown()} 后新提交抛 {@link RejectedExecutionException}
 *       （严格模式：停机不是静默吞，终端态不自动复活）——生产挂 SerialIntegration.onRelease；</li>
 *   <li>测试缝：bind(null) 拒绝；resetForTest 供测试独占默认池。</li>
 * </ul>
 */
public class SerialSdkTimersTest {

    @Before
    public void setUp() {
        // 其他测试类可能经 SerialIntegration.onRelease 关过默认池；本类要验证默认池形态，
        // 先复位取一个全新池（resetForTest 仅测试基建，生产无调用方）
        SerialSdkTimers.resetForTest();
    }

    @After
    public void tearDown() {
        SerialSdkTimers.unbindForTest();
        SerialSdkTimers.shutdown();
        MdcContext.clearCoordinate();
        TraceContext.clearTraceId();
    }

    @Test
    public void fireAfterPropagatesSubmitterMdcAndEnsuresTraceId() {
        FakeSerialTimers timers = new FakeSerialTimers();
        try {
            TraceContext.clearTraceId();
            MdcContext.setCoordinate("com.ecat:integration-sailhero");
            final AtomicReference<String> seenCoordinate = new AtomicReference<>();
            final AtomicReference<String> seenTraceId = new AtomicReference<>();
            SerialSdkTimers.fireAfter(() -> {
                seenCoordinate.set(MdcContext.getCoordinate());
                seenTraceId.set(TraceContext.getTraceId());
            }, 5L);

            // 提交侧快照含坐标（提交时捕获语义）
            assertEquals("提交时捕获 coordinate（快照面）", "com.ecat:integration-sailhero",
                    timers.lastShot().submitMdc.get(MdcContext.INTEGRATION_COORDINATE_KEY));

            // 到拍侧：清空当前线程 MDC 后触发，命令内看到恢复出的坐标 + 补生成的 traceId
            TraceContext.restore(null);
            MdcContext.clearCoordinate();
            timers.fire(0);
            assertEquals("到拍恢复提交时 coordinate", "com.ecat:integration-sailhero", seenCoordinate.get());
            assertNotNull("无 traceId 的提交在到拍时补生成", seenTraceId.get());
        } finally {
            timers.close();
        }
    }

    @Test
    public void defaultPoolIsDaemonNamedEcatSerialSchedAndShutdownIsTerminal() throws Exception {
        // 不 bind：走生产默认池（懒创建）
        final CountDownLatch fired = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        final AtomicReference<Boolean> daemon = new AtomicReference<>();
        SerialSdkTimers.fireAfter(() -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            fired.countDown();
        }, 0L);

        assertTrue("默认池 0ms 单发必须在观察窗内到拍", fired.await(5, TimeUnit.SECONDS));
        assertTrue("线程名须为 ecat-serial-sched-N（实际 " + threadName.get() + "）",
                threadName.get().matches("ecat-serial-sched-\\d+"));
        assertEquals("SDK 定时线程必须 daemon（不阻 JVM 退出）", Boolean.TRUE, daemon.get());

        // 停机钩子：shutdown 后新提交拒绝（终端态，不静默、不自动复活）
        SerialSdkTimers.shutdown();
        try {
            SerialSdkTimers.fireAfter(() -> { }, 1L);
            fail("停机后新提交必须 RejectedExecutionException");
        } catch (RejectedExecutionException expected) { }
    }

    @Test
    public void bindForTestRejectsNull() {
        try {
            SerialSdkTimers.bindForTest(null);
            fail("bind(null) 必须 IllegalArgumentException（解除用 unbindForTest）");
        } catch (IllegalArgumentException expected) { }
    }

    /** 真实定时器形态冒烟：forScheduledExecutor 适配真 STPE（联调用测试自备池）。 */
    @Test
    public void forScheduledExecutorAdaptsRealStpe() throws Exception {
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1,
                new NamedThreadFactory("serial-sdk-adhoc-test", true));
        try {
            SerialSdkTimers.bindForTest(SerialSdkTimers.forScheduledExecutor(stpe));
            final CountDownLatch fired = new CountDownLatch(1);
            SerialSdkTimers.fireAfter(fired::countDown, 0L);
            assertTrue("经适配器提交的真实 STPE 单发必须到拍", fired.await(5, TimeUnit.SECONDS));
        } finally {
            SerialSdkTimers.unbindForTest();
            stpe.shutdownNow();
        }
    }

    /** runner() 在当前缝上取 core PeriodicRunner：bind 替身后周期链整体走替身（lambda 逐调用解析）。 */
    @Test
    public void runnerFollowsBoundSeamImmediately() {
        FakeSerialTimers timers = new FakeSerialTimers();
        try {
            SerialSdkTimers.runner().fireAfter(() -> { }, 10L);
            assertEquals("runner 的单发必须经当前缝提交（bind 替身即时生效）", 1, timers.shots.size());
        } finally {
            timers.close();
        }
    }
}
