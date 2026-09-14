package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Mdc.MdcContext;

/**
 * SerialPolling 咽喉 owner 派生（io-resource-owner 设计 §5.2 咽喉保留面）：start()
 * 同位置以 tryDeriveOwner(host) 派生 owner 作 MDC 注入源——scopeOf(owner) 设备三键
 * 同刻同源（owner 路径优先，{@code SerialPollingSdkTest} 的无 entryId 设备用例锁
 * scopeOf(host) 回落路径，两分支互補）。容忍边界：lambda 假宿主 / 空 entry 设备宿主
 * 派生失败不抛、轮询照常（严格 fail-fast 留在 register(SerialInfo, host) 账本级，
 * 设计把守卫放在那里）。视图锁归属不在此处关联——咽喉 associateOwner 已随
 * LEGACY 注册签名同批删除，视图归属由注册入口携带。
 */
public class SerialPollingOwnerThroatTest {

    private static final long PERIOD_MS = 150L;
    private static final long AWAIT_MS = 5_000L;

    private ScheduledExecutorService timers;
    private PollingHandle handle;
    private SerialSource source;

    @Before
    public void setUp() {
        SerialSdkTimers.resetForTest();
        timers = Executors.newScheduledThreadPool(1,
                new NamedThreadFactory("serial-throat-test", true));
        SerialSdkTimers.bindForTest(SerialSdkTimers.forScheduledExecutor(timers));
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("THROAT-PORT", 9600, 8, 1, 0), 1, null);
        source = bridgedSource(port);
    }

    @After
    public void tearDown() {
        if (handle != null) {
            handle.cancel();
        }
        SerialSdkTimers.unbindForTest();
        timers.shutdownNow();
        SerialIoPool.resetForTest();
    }

    /** 桥接到真实端口的 SerialSource mock：锁状态机穿过真实实现（沿 SerialPollingSdkTest 范式）。 */
    private SerialSource bridgedSource(SerialSourcePort port) {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenAnswer(inv -> port.acquire());
        when(source.acquire(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> port.acquire(
                inv.getArgument(0, Long.class), inv.getArgument(1, TimeUnit.class)));
        when(source.acquirePollingBounded(anyLong())).thenAnswer(inv -> port.acquirePollingBounded(
                null, inv.getArgument(0, Long.class)));
        when(source.release(anyString())).thenAnswer(inv -> port.release(inv.getArgument(0, String.class)));
        when(source.getPortName()).thenReturn(port.getPortName());
        when(source.getTimeout()).thenReturn(port.getTimeout());
        return source;
    }

    /** 设备宿主（entry 带 entryId+coordinate）：owner 派生路径——轮体 MDC 携带设备三键（同刻同源）。 */
    @Test
    public void deviceHostStartInjectsOwnerDerivedMdcIntoRounds() throws Exception {
        DeviceBase device = new HostDevice("entry-1", "com.ecat:integration-throat-test");
        CountDownLatch roundBegan = new CountDownLatch(1);
        AtomicReference<String> seenDeviceId = new AtomicReference<>();
        AtomicReference<String> seenCoordinate = new AtomicReference<>();

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    seenDeviceId.set(MDC.get(MdcContext.DEVICE_ID_KEY));
                    seenCoordinate.set(MDC.get(MdcContext.INTEGRATION_COORDINATE_KEY));
                    roundBegan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("首轮必须执行", roundBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals("round 线程 MDC 含 device.id（owner.mdcEntries 派生）", device.getId(), seenDeviceId.get());
        assertEquals("round 线程 MDC 含 coordinate（与 deviceId 同刻同源）",
                "com.ecat:integration-throat-test", seenCoordinate.get());
        assertNull("起链线程（本测试线程）不得残留设备键", MDC.get(MdcContext.DEVICE_ID_KEY));
    }

    /** lambda 假宿主（测试形态，非 DeviceBase/IntegrationBase）：派生失败容忍，轮询照常。 */
    @Test
    public void lambdaHostStartToleratedRoundsStillRun() throws Exception {
        CountDownLatch roundBegan = new CountDownLatch(1);

        handle = SerialPolling.on(action -> { }, source)
                .round(src -> {
                    roundBegan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("派生失败不影响轮询运行", roundBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
    }

    /** 空 entry 设备宿主（@Deprecated Map 构造器路径形态）：派生缺主键被容忍，不抛照跑。 */
    @Test
    public void emptyEntryDeviceHostTolerated() throws Exception {
        DeviceBase device = new HostDevice(null, null);
        CountDownLatch roundBegan = new CountDownLatch(1);

        handle = SerialPolling.on(device, source)
                .round(src -> {
                    roundBegan.countDown();
                    return CompletableFuture.completedFuture(true);
                })
                .every(PERIOD_MS, TimeUnit.MILLISECONDS)
                .start();

        assertTrue("空 entry 容忍路径轮询照常运行", roundBegan.await(AWAIT_MS, TimeUnit.MILLISECONDS));
    }

    /** 最小设备桩：entryId/coordinate 可空（空=派生失败容忍路径）。 */
    private static final class HostDevice extends DeviceBase {
        HostDevice(String entryId, String coordinate) {
            super(entryWith(entryId, coordinate));
        }

        private static ConfigEntry entryWith(String entryId, String coordinate) {
            ConfigEntry entry = new ConfigEntry();
            entry.setEntryId(entryId);
            entry.setCoordinate(coordinate);
            entry.setData(new HashMap<>());
            return entry;
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void release() {
        }
    }
}
