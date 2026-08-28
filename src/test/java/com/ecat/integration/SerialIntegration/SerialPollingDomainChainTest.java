package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

/**
 * SerialPolling 周期链的域自持网格语义（29 号 v2 S1：SerialSdkTimers + core
 * PeriodicRunner 替代 SdkSchedulerResolver 引擎；网格策略域侧实现照 httpserver
 * PollingSchedule 形态）——fake 定时缝 + 注入纳米钟驱动，零线程零真实时钟：
 * <ul>
 *   <li>首发：默认 initialDelay=0 即发；initialDelay(n) 首发延迟 n ms；</li>
 *   <li>fixedDelay：完成点+period 重排（在飞不提前发射，事务完成点起算）；</li>
 *   <li>fixedRate：名义网格推进、在飞跨拍跳过（不滞后补跑）；</li>
 *   <li>过期即弃：到拍滞后超一个整周期 → 本轮丢弃（skips=lag/period+1 推进锚点）；</li>
 *   <li>delay()：命令间留隙经域定时器单发（SdkSchedulerResolver 引擎路径退役）；</li>
 *   <li>锁忙轮/异常轮任何终态都重排（永不注销）。</li>
 * </ul>
 */
public class SerialPollingDomainChainTest {

    private FakeSerialTimers timers;
    private SerialSource source;
    private final AtomicLong nanoClock = new AtomicLong(0L);
    /** 事务硬超时执法的旁路调度器：与周期链共缝（都走 SerialSdkTimers）会混入捕获面，
     *  经其自有 bind 缝引开——本测只断言周期链的拍。 */
    private ScheduledExecutorService timeoutEnforcer;

    private static final long PERIOD_MS = 1_000L;
    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1L);

    @Before
    public void setUp() {
        timers = new FakeSerialTimers();
        timeoutEnforcer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "domain-chain-timeout-test");
            t.setDaemon(true);
            return t;
        });
        SerialTimeoutScheduler.bind(timeoutEnforcer);
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("DOMAIN-CHAIN-PORT", 9600, 8, 1, 0), 1, null);
        source = bridgedSource(port);
    }

    @After
    public void tearDown() {
        SerialTimeoutScheduler.unbind();
        timeoutEnforcer.shutdownNow();
        timers.close();
    }

    private SerialPolling ready() {
        return SerialPolling.on(action -> { }, source)
                .round(src -> CompletableFuture.completedFuture(true))
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .withNanoClock(nanoClock::get);
    }

    /** 桥接到真实端口锁状态机的 SerialSource mock（不 openPort，零 jSerialComm 副作用）。 */
    private static SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.tryAcquire()).thenAnswer(inv -> port.tryAcquire());
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        when(source.getPortName()).thenReturn(port.getPortName());
        when(source.getTimeout()).thenReturn(port.getTimeout());
        return source;
    }

    // ==================== 首发：initialDelay（默认 0 = 立即） ====================

    @Test
    public void startFiresFirstRoundImmediatelyByDefault() {
        ready().start();
        assertEquals("首发即发（对位原 initialDelay=0，与存量设备仓行为一致）", 1, timers.shots.size());
        assertEquals(0L, timers.shots.get(0).delayMillis);
    }

    @Test
    public void initialDelayDefersFirstShot() {
        ready().initialDelay(600L, TimeUnit.MILLISECONDS).start();
        assertEquals("initialDelay 进首发延迟（域链 firstDelay 事件）", 1, timers.shots.size());
        assertEquals(600L, timers.shots.get(0).delayMillis);
    }

    // ==================== fixedDelay：完成点+period ====================

    @Test
    public void fixedDelayRearmsFromSettlePoint() {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        SerialPolling.on(action -> { }, source)
                .round(src -> pending)
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0);
        assertEquals("在飞期间不得重排（单飞）", 1, timers.shots.size());

        nanoClock.addAndGet(2L * SECOND_NANOS);   // 在飞 2s
        pending.complete(Boolean.TRUE);
        assertEquals("结算后重排下一拍", 2, timers.shots.size());
        assertEquals("fixedDelay=完成点+period（名义漂移不回收）", PERIOD_MS, timers.shots.get(1).delayMillis);
    }

    // ==================== fixedRate：名义网格+跨拍跳过 ====================

    @Test
    public void fixedRateAdvancesNominalGridAndSkipsCrossedTicks() {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        SerialPolling.on(action -> { }, source)
                .round(src -> pending)
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .fixedRate()
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0);   // 首拍 t0 发射，事务在飞

        nanoClock.addAndGet(3L * SECOND_NANOS);   // 跨过 3 个网格拍
        pending.complete(Boolean.TRUE);
        long rearmDelay = timers.shots.get(1).delayMillis;
        assertEquals("fixedRate 在飞 3s（period 1s）后：锚点推进到首个未来网格点（t0+4s，即 1s 后）",
                PERIOD_MS, rearmDelay);
    }

    // ==================== 过期即弃：到拍滞后超一个整周期 ====================

    @Test
    public void staleFireDropsRoundAndAdvancesAnchor() {
        ready().start();
        assertEquals(1, timers.shots.size());

        // 到拍滞后 2.5 个周期：onFire 判过期（lag=2.5s > period 1s）→ skips=2.5/1+1=3
        nanoClock.addAndGet((5L * SECOND_NANOS) / 2);
        timers.fire(0);

        assertEquals("过期轮不执行、重排下一拍（round 体未跑：无第 3 拍提交前的多余记录）",
                2, timers.shots.size());
        assertEquals("锚点推进 skips×period 到首个未来网格（t0+3s，即 0.5s 后）", 500L,
                timers.shots.get(1).delayMillis);
    }

    // ==================== 锁忙/异常轮：任何终态都重排（永不注销） ====================

    @Test
    public void lockBusyRoundSettlesNormallyAndRearms() {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("DOMAIN-BUSY-PORT", 9600, 8, 1, 0), 1, null);
        String held = port.acquire(1, TimeUnit.SECONDS);
        assertTrue(held != null);
        SerialPolling polling = SerialPolling.on(action -> { }, bridgedSource(port))
                .round(src -> CompletableFuture.completedFuture(true))
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .withNanoClock(nanoClock::get);
        PollingHandle handle = polling.start();
        timers.fire(0);   // 锁忙轮：tryAcquire 即弃 → CF 瞬时异常完成 → SDK 内化正常结算

        assertEquals("锁忙轮结算后必须重排（网格不变，永不注销）", 2, timers.shots.size());
        assertEquals("锁忙轮按正常结算重排（fixedDelay=结算点+period）", PERIOD_MS,
                timers.shots.get(1).delayMillis);
        assertTrue("锁忙轮不得注销链", handle.isRunning());
        port.release(held);
    }

    @Test
    public void failedRoundSettlesAndRearms() {
        SerialPolling.on(action -> { }, source)
                .round(src -> {
                    throw new IllegalStateException("round begin boom");
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .withNanoClock(nanoClock::get)
                .start();
        timers.fire(0);   // begin 同步抛=异常轮等价

        assertEquals("异常轮任何终态都重排（永不注销）", 2, timers.shots.size());
        assertEquals(PERIOD_MS, timers.shots.get(1).delayMillis);
    }

    // ==================== delay()：域定时器单发 ====================

    @Test
    public void delayRoutesThroughDomainTimers() {
        SerialPolling polling = SerialPolling.on(action -> { }, source)
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .interCommandDelayMs(200L)
                .withNanoClock(nanoClock::get);

        CompletableFuture<Void> delayed = polling.delay();
        assertEquals("no-arg delay() 经域定时器单发（interCommandDelayMs 配置值）", 1, timers.shots.size());
        assertEquals(200L, timers.lastShot().delayMillis);
        assertFalse(delayed.isDone());

        timers.fire(0);
        assertTrue("到点后 delay future 完成", delayed.isDone());

        // 公有糖 delay(ms)：一次性延迟收编入口，同样走域定时器
        CompletableFuture<Void> sugared = polling.delay(50L, TimeUnit.MILLISECONDS);
        assertEquals(2, timers.shots.size());
        assertEquals(50L, timers.lastShot().delayMillis);
        timers.fire(1);
        assertTrue(sugared.isDone());
    }

    // ==================== cancel：撤销待发拍 ====================

    @Test
    public void cancelRevokesPendingShot() {
        PollingHandle handle = ready().start();
        handle.cancel();
        assertTrue("cancel 后句柄不在调度", !handle.isRunning());
        assertTrue("cancel 撤销待发单发（PeriodicChain 竞态收口）",
                ((FakeSerialTimers.StubFuture) timers.shots.get(0).future).cancelled);
    }
}
