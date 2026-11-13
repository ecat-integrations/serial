package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ecat.core.CommTrace.ResourceOwner;

/**
 * 【D3 路径甲第一步】在飞事务 × 并发 reconfigure 竞态复现测试（POC）。
 *
 * <p>场景（docs/serial-execution-model/01-research/2026-11-13-reconfigure-race-verifiability.md §2.3 草案）：
 * 事务侧持锁在飞写卡死在车道线程的 writeBytes 内 → 配置流线程 register(同口、不同物理参数)
 * 触发 applyReconfiguredSettings 的 stopPolling→closePort→openPort（全程不取事务锁）→
 * 释放在飞写 → 断言竞态窗后的逻辑不变量。
 *
 * <p>强不变量（fd 代数手法）：static-mock {@code SerialPort.getCommPort} 使每次 openPort
 * 都产出一个新的一代 mock；任何在「已 close 的代」上到达（开始执行）的写=写往已关闭 fd=违例。
 * 旧代上在 close 之前已开始的那次在飞写是合法的（开始于 close 前，jSerialComm 真实语义由内核
 * 决定，mock 层只覆盖逻辑不变量）。
 *
 * <p>断言集（全部确定性，latch 同步，无 sleep）：
 * <ol>
 *   <li>fd 代数：无任何写在已 close 的代上开始执行；</li>
 *   <li>重开次数：getCommPort 恰好被调用 1 次（reconfigure 的 close+reopen），无额外重开；</li>
 *   <li>幽灵锁：release 后新 acquire 必须立即授予（无 currentKey 残留）；</li>
 *   <li>终态：serialPort 唯一、打开态、且为重开后的新一代；serialInfo 已换新物理参数；</li>
 *   <li>在飞写 future 确定性完成；reconfigure 后的新事务写落在新一代 mock 上。</li>
 * </ol>
 *
 * <p>RED=竞态实证；GREEN=现状在该场景未复现（同为有效结果，见研究档案记录）。
 */
public class SerialReconfigureRaceTest {

    private static final String PORT = "/dev/null";
    private static final String COORD = "com.ecat:integration-reconfigure-race-test";

    /** 端口代（generation）：一次 openPort 对应一个新 mock；closed 后到达的写=违例。 */
    static final class PortGeneration {
        final SerialPort mock;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicInteger writesStartedOnClosedPort = new AtomicInteger(0);
        final AtomicInteger writeInvocations = new AtomicInteger(0);

        PortGeneration(SerialPort mock) {
            this.mock = mock;
        }
    }

    private MockedStatic<SerialPort> commPortStatic;
    private final List<PortGeneration> generations = new CopyOnWriteArrayList<>();
    private CountDownLatch writeEntered;
    private CountDownLatch writeRelease;
    private SerialIntegration integration;

    @Before
    public void setUp() {
        writeEntered = new CountDownLatch(1);
        writeRelease = new CountDownLatch(1);
        // static-mock getCommPort：openPort 的换新对象点收编为「新一代 mock」，代数可观测。
        // 注意 MockedStatic 仅在创建线程（本测试主线程=reconfigure 线程）生效——本场景
        // getCommPort 的合法调用方正是指定线程的车道线程外重开路径，车道线程侧若出现
        // 未预期的 getCommPort 调用会落到真实实现（caveats，见研究档案）。
        commPortStatic = Mockito.mockStatic(SerialPort.class);
        commPortStatic.when(() -> SerialPort.getCommPort(PORT)).thenAnswer(inv -> newGeneration().mock);

        integration = new SerialIntegration();
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(PORT, 9600, 8, 1, 0), 1, integration);
        PortGeneration gen0 = newGeneration();
        // 第 0 代（初始 mock）的写卡死：进入 writeBytes 即 writeEntered.countDown，然后等释放
        when(gen0.mock.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            recordWriteStart(gen0);
            writeEntered.countDown();
            if (!writeRelease.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试同步失败：writeRelease latch 15s 未释放");
            }
            byte[] b = inv.getArgument(0);
            return b.length;
        });
        port.serialPort = gen0.mock;
        integration.serialPortsPutForTest(PORT, port);
    }

    @After
    public void tearDown() {
        commPortStatic.close();
    }

    private PortGeneration newGeneration() {
        SerialPort m = mock(SerialPort.class);
        PortGeneration g = new PortGeneration(m);
        final AtomicBoolean open = new AtomicBoolean(true);
        when(m.isOpen()).thenAnswer(inv -> open.get());
        doAnswer(inv -> {
            open.set(false);
            g.closed.set(true);
            return true;
        }).when(m).closePort();
        when(m.bytesAvailable()).thenReturn(0);
        when(m.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            recordWriteStart(g);
            byte[] b = inv.getArgument(0);
            return b.length;
        });
        generations.add(g);
        return g;
    }

    /** 写开始执行的判定点：若此时本代已被 close，即「写往已关闭 fd」违例。 */
    private static void recordWriteStart(PortGeneration g) {
        g.writeInvocations.incrementAndGet();
        if (g.closed.get()) {
            g.writesStartedOnClosedPort.incrementAndGet();
        }
    }

    private static ResourceOwner deviceOwner(String deviceId) {
        return ResourceOwner.device(COORD, "entry-1", deviceId);
    }

    /**
     * 在飞写 × 并发 reconfigure：写卡死在车道线程 writeBytes 内时，配置流线程 register
     * 触发 closePort+openPort。释放写后断言竞态窗的全部逻辑不变量。
     */
    @Test
    public void inflightWriteConcurrentReconfigure_noWriteOnClosedFd_singleReopen_noGhostLock() throws Exception {
        SerialSourcePort port = integration.serialPortsGet(PORT);

        // 事务侧：持锁 + 在飞写（车道线程内卡死在 writeBytes）
        String key = port.acquire();
        assertNotNull("测试前置：取得事务锁", key);
        CompletableFuture<Boolean> inflight = port.asyncSendData(new byte[]{0x01});
        assertTrue("测试前置：写应已进入 writeBytes（车道线程内执行中）",
                writeEntered.await(5, TimeUnit.SECONDS));

        // reconfigure 侧（本线程=模拟配置流提交线程，与车道线程并发）
        integration.register(new SerialInfo(PORT, 19200, 8, 1, 0), deviceOwner("dev-1"));

        // 释放竞态窗内的在飞写
        writeRelease.countDown();
        assertEquals("在飞写 future 应确定性完成", Boolean.TRUE, inflight.get(5, TimeUnit.SECONDS));

        // 不变量①（fd 代数）：任何一代上都不存在「close 之后才开始执行」的写
        for (int i = 0; i < generations.size(); i++) {
            assertEquals("第 " + i + " 代端口上出现 close 后到达的写（写往已关闭 fd）",
                    0, generations.get(i).writesStartedOnClosedPort.get());
        }
        // 不变量②（重开次数）：reconfigure 的 close+reopen 恰好换新一次端口对象，无额外重开
        commPortStatic.verify(() -> SerialPort.getCommPort(PORT), Mockito.times(1));
        // 不变量③（幽灵锁）：release 后新 acquire 必须立即授予
        port.release(key);
        String reacquireKey = port.acquire(2, TimeUnit.SECONDS, null);
        assertNotNull("release 后锁应立即可授予（无幽灵锁/currentKey 残留）", reacquireKey);
        port.release(reacquireKey);
        // 不变量④（终态）：serialPort 唯一、打开态、为重开后的新一代；新参数已生效
        assertEquals("应有且仅有两代端口（初始代 + reconfigure 重开代）", 2, generations.size());
        SerialPort finalPort = port.serialPort;
        assertNotNull(finalPort);
        assertTrue("终态端口应为打开态", finalPort.isOpen());
        assertSame("终态 serialPort 应为 reconfigure 重开后的新一代", generations.get(1).mock, finalPort);
        assertEquals("reconfigure 后端口必须持有新 baudrate", Integer.valueOf(19200), port.serialInfo.baudrate);
        // 不变量⑤（可用性）：竞态窗之后的新事务写落在新一代 mock 上（旧代不再被写）
        String newKey = port.acquire();
        assertNotNull(newKey);
        CompletableFuture<Boolean> after = port.asyncSendData(new byte[]{0x02});
        assertEquals(Boolean.TRUE, after.get(5, TimeUnit.SECONDS));
        verify(generations.get(1).mock, timeout(2000)).writeBytes(new byte[]{0x02}, 1L);
        port.release(newKey);
        // 第 0 代只被在飞写调用过一次（close 前开始的那次），close 后零写
        verify(generations.get(0).mock, Mockito.times(1)).writeBytes(any(byte[].class), anyLong());
    }
}
