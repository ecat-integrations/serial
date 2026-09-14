package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import com.ecat.core.CommTrace.CommTraceBuffer;
import com.ecat.core.CommTrace.CommTraceDirection;
import com.ecat.core.CommTrace.CommTraceEvent;
import com.ecat.core.CommTrace.CommTraceFilter;
import com.ecat.core.CommTrace.CommTraceTransport;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Task.NamedThreadFactory;
import com.fazecast.jSerialComm.SerialPort;

/**
 * 串口 TX/RX 捕获点权威归因（io-resource-owner 设计 §5.2 捕获点 + §8 行 2/3/4）：
 * 持锁事务的字节同读 lockAcquireOwner 作权威参数（TX=doSendWrite 唯一写出口、
 * RX=handleIncomingData 读路径唯一入口——端口共享读线程永无任务 MDC，锁即判官）；
 * 无锁路径保持现状（无 MDC 时如实 null）；identity 串直接构造的视图（LEGACY 形态）
 * 不参与锁权威（无设备身份字段，注入会压制线程 MDC 归因）。
 */
public class SerialOwnerAttributionTest {

    private static final String COORDINATE = "com.ecat:integration-attribution-test";

    @After
    public void tearDown() {
        SerialIoPool.resetForTest();
    }

    private static ResourceOwner deviceOwner(String entryId, String deviceId) {
        return ResourceOwner.device(COORDINATE, entryId, deviceId);
    }

    /** 真实 SerialSourcePort + mock 串口（写路径走真实 doSendWrite 方法体），registerSource 已挂真实源。 */
    private SerialSourcePort portWithWritableMock(String portName, ResourceOwner owner) {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(portName, 9600, 8, 1, 0), 1, null);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenReturn(8);
        port.serialPort = serialPort;
        new SerialSource(port, owner);
        return port;
    }

    private List<CommTraceEvent> queryTx(String portName, long since) {
        return CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, portName, null, CommTraceDirection.TX, null),
                10, since);
    }

    private List<CommTraceEvent> queryRx(String portName, long since) {
        return CommTraceBuffer.instance().query(
                new CommTraceFilter(CommTraceTransport.SERIAL, portName, null, CommTraceDirection.RX, null),
                10, since);
    }

    /** 持锁 TX：捕获点读 lockAcquireOwner，owner 三字段整组投影（无 host 的字段构造 owner——name 如实 null）。 */
    @Test
    public void heldLockTxAttributesToDeviceOwner() throws Exception {
        SerialSourcePort port = portWithWritableMock("ATTR-TX-PORT",
                deviceOwner("entry-1", "dev-tx-1"));
        SerialSource source = port.getConnectedSources().get(0);
        long since = CommTraceBuffer.instance().latestSeq();

        String key = source.acquire();
        assertNotNull(key);
        assertTrue(source.asyncSendData(new byte[]{0x01, 0x03}).get(10, TimeUnit.SECONDS));
        source.release(key);

        List<CommTraceEvent> events = queryTx("ATTR-TX-PORT", since);
        assertEquals("写路径产生 1 帧 TX", 1, events.size());
        CommTraceEvent e = events.get(0);
        assertEquals("TX 权威归属 deviceId（持锁临界区直读）", "dev-tx-1", e.getDeviceId());
        assertEquals("TX 权威归属 coordinate（与 deviceId 同刻同源）", COORDINATE, e.getCoordinate());
        assertNull("无展示宿主的 owner——deviceName 如实 null（不猜测填充）", e.getDeviceName());
    }

    /** 持锁 RX：读线程无任务 MDC，归属=当前持锁 owner（同口判官=事务锁）。 */
    @Test
    public void heldLockRxAttributesToDeviceOwner() {
        SerialSourcePort port = portWithWritableMock("ATTR-RX-PORT",
                deviceOwner("entry-1", "dev-rx-1"));
        SerialSource source = port.getConnectedSources().get(0);
        long since = CommTraceBuffer.instance().latestSeq();

        String key = source.acquire();
        assertNotNull(key);
        byte[] frame = new byte[]{0x01, 0x03, 0x02, 0x12, 0x34};
        port.handleIncomingData(frame, frame.length);
        source.release(key);

        List<CommTraceEvent> events = queryRx("ATTR-RX-PORT", since);
        assertEquals("读路径产生 1 帧 RX", 1, events.size());
        assertEquals("RX 权威归属=持锁 owner（端口共享读线程，锁即判官）",
                "dev-rx-1", events.get(0).getDeviceId());
    }

    /** 无锁路径现状保持：迟到/unsolicited 字节锁已清 → 不误挂旧 owner，无 MDC 时如实 null。 */
    @Test
    public void unlockedTxKeepsCurrentBehaviorNullDevice() throws Exception {
        SerialSourcePort port = portWithWritableMock("ATTR-NOLOCK-PORT",
                deviceOwner("entry-1", "dev-nolock"));
        SerialSource source = port.getConnectedSources().get(0);
        long since = CommTraceBuffer.instance().latestSeq();

        assertTrue(source.asyncSendData(new byte[]{0x01}).get(10, TimeUnit.SECONDS));

        List<CommTraceEvent> events = queryTx("ATTR-NOLOCK-PORT", since);
        assertEquals(1, events.size());
        assertNull("无锁 TX 不挂注册 owner（权威=当前住户，非常住人口）", events.get(0).getDeviceId());
        assertNull(events.get(0).getDeviceName());
        assertNull(events.get(0).getCoordinate());
    }

    /**
     * LEGACY 注册不参与锁权威：旧签名注册的视图（无关联时）保持现状——LEGACY owner 无设备
     * 身份字段，注入反而会压制线程 MDC 归因（W2 契约：owner 存在则整组投影不与 MDC 混搭），
     * 过渡期行为不得回退。
     */
    @Test
    public void legacyRegistrationDoesNotInjectLockOwner() throws Exception {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("ATTR-LEGACY-PORT", 9600, 8, 1, 0), 1, null);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenReturn(4);
        port.serialPort = serialPort;
        SerialSource source = new SerialSource(port, "legacy-identity-1");
        long since = CommTraceBuffer.instance().latestSeq();

        String key = source.acquire();
        assertNotNull(key);
        assertTrue(source.asyncSendData(new byte[]{0x01}).get(10, TimeUnit.SECONDS));
        source.release(key);

        List<CommTraceEvent> events = queryTx("ATTR-LEGACY-PORT", since);
        assertEquals(1, events.size());
        assertNull("LEGACY 注册不注入锁权威（过渡期现状保持）", events.get(0).getDeviceId());
    }

    /** 多设备同口（B9 判官）：注入归属=各视图自己的注册 owner，锁交替期间帧归属逐笔正确。 */
    @Test
    public void multiDeviceSamePortAlternatingOwnershipPerTransaction() throws Exception {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo("ATTR-SHARED-PORT", 9600, 8, 1, 0), 1, null);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.writeBytes(any(byte[].class), anyLong())).thenReturn(4);
        port.serialPort = serialPort;
        SerialSource sourceA = new SerialSource(port, deviceOwner("entry-a", "dev-a"));
        SerialSource sourceB = new SerialSource(port, deviceOwner("entry-b", "dev-b"));
        long since = CommTraceBuffer.instance().latestSeq();

        String keyA = sourceA.acquire();
        assertNotNull(keyA);
        assertTrue(sourceA.asyncSendData(new byte[]{0x01}).get(10, TimeUnit.SECONDS));
        sourceA.release(keyA);

        String keyB = sourceB.acquire();
        assertNotNull(keyB);
        assertTrue(sourceB.asyncSendData(new byte[]{0x02}).get(10, TimeUnit.SECONDS));
        sourceB.release(keyB);

        List<CommTraceEvent> events = queryTx("ATTR-SHARED-PORT", since);
        assertEquals(2, events.size());
        // query 返回旧→新序：逐笔与事务时序一一对应（每笔各挂自己的 owner）
        assertEquals("第一笔事务归属 dev-a", "dev-a", events.get(0).getDeviceId());
        assertEquals("第二笔事务归属 dev-b（同口判官=事务锁，逐笔交替）", "dev-b", events.get(1).getDeviceId());
    }

    /**
     * 并发多口不串台（B4，沿用 core concurrentMultiPortAppendEachPortAttributionCorrect 范式）：
     * 两口并发持锁 TX/RX，各口帧归属本口 owner——attribution 读的是各口自己的 lockAcquireOwner。
     */
    @Test
    public void concurrentMultiPortAttributionNoCrossTalk() throws Exception {
        int ports = 2;
        SerialSourcePort[] portObjects = new SerialSourcePort[ports];
        for (int i = 0; i < ports; i++) {
            portObjects[i] = portWithWritableMock("ATTR-CONC-PORT-" + i,
                    deviceOwner("entry-" + i, "dev-conc-" + i));
        }
        long since = CommTraceBuffer.instance().latestSeq();

        ExecutorService pool = Executors.newFixedThreadPool(ports,
                new NamedThreadFactory("attr-conc-test", true));
        CountDownLatch bothHeld = new CountDownLatch(ports);
        CountDownLatch bothSent = new CountDownLatch(ports);
        AtomicInteger unexpected = new AtomicInteger();
        for (int t = 0; t < ports; t++) {
            final int idx = t;
            pool.execute(() -> {
                try {
                    SerialSource source = portObjects[idx].getConnectedSources().get(0);
                    String key = source.acquire();
                    assertNotNull(key);
                    bothHeld.countDown();
                    assertTrue(bothHeld.await(10, TimeUnit.SECONDS));
                    assertTrue(source.asyncSendData(new byte[]{(byte) idx}).get(10, TimeUnit.SECONDS));
                    byte[] frame = new byte[]{(byte) idx, 0x03};
                    portObjects[idx].handleIncomingData(frame, frame.length);
                    source.release(key);
                } catch (Throwable e) {
                    unexpected.incrementAndGet();
                } finally {
                    bothSent.countDown();
                }
            });
        }
        assertTrue(bothSent.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals("无未预期异常", 0, unexpected.get());

        for (int i = 0; i < ports; i++) {
            String portName = "ATTR-CONC-PORT-" + i;
            List<CommTraceEvent> tx = queryTx(portName, since);
            assertEquals(1, tx.size());
            assertEquals("口内 TX 归属本口 owner（并发不串台）", "dev-conc-" + i, tx.get(0).getDeviceId());
            List<CommTraceEvent> rx = queryRx(portName, since);
            assertEquals(1, rx.size());
            assertEquals("口内 RX 归属本口 owner（并发不串台）", "dev-conc-" + i, rx.get(0).getDeviceId());
        }
    }
}
