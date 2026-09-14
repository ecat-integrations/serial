package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceKind;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.ResourceRef;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;

/**
 * 资源账本 owner 化与精准查询契约（io-resource-owner 设计 §5.2 + §8 行「引用计数 owner 化」
 * + §9-10）：同口多 owner 注册/末源才拆、LEGACY 与类型化混存、同设备重注册同键去重、
 * get*Info 一对一精准（未注册 null / 一名多资源明确异常 / RECONFIGURE 后活配置）、
 * get*Owners 按层折叠、register(info, host) 三合一（owner 派生 + onRemove 绑定 + 终态守卫）。
 *
 * <p>端口对象直接构造 + mock 串口注入（沿 ReconfigureSettingsTest 范式，绕过真实 openPort）。
 */
public class SerialOwnerLedgerTest {

    private static final String COORD = "com.ecat:integration-ledger-test";
    private static final String PORT = "/dev/null";

    private static ResourceOwner deviceOwner(String entryId, String deviceId) {
        return ResourceOwner.device(COORD, entryId, deviceId);
    }

    /** mock 串口 isOpen 跟随 closePort 翻转（重建/拆港路径依赖 isOpen 短路）。 */
    private SerialIntegration integrationWithPort(String portName, SerialInfo info) {
        SerialIntegration integration = new SerialIntegration();
        SerialSourcePort port = new SerialSourcePort(info, 1, integration);
        SerialPort serialPort = mock(SerialPort.class);
        final boolean[] open = {true};
        when(serialPort.isOpen()).thenAnswer(inv -> open[0]);
        doAnswer(inv -> {
            open[0] = false;
            return true;
        }).when(serialPort).closePort();
        port.serialPort = serialPort;
        integration.serialPortsPutForTest(portName, port);
        return integration;
    }

    private static SerialInfo info(String portName, int timeout) {
        return new SerialInfo(portName, 9600, 8, 1, 0, 0, timeout);
    }

    /** 入口最小设备桩（宿主派生路径）：entry 带 entryId+coordinate，of(host) 可派生 DEVICE 层。 */
    private static final class HostDevice extends DeviceBase {
        HostDevice(String entryId) {
            super(entryWith(entryId));
        }

        private static ConfigEntry entryWith(String entryId) {
            ConfigEntry entry = new ConfigEntry();
            entry.setEntryId(entryId);
            entry.setCoordinate(COORD);
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

    // ==================== B5：引用计数 owner 化 ====================

    /** 同口多 owner：逐个注销只减账，末源注销才拆港（既有引用计数语义不回退）。 */
    @Test
    public void multiOwnerUnregister_lastTeardownDismantlesPort() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        SerialSource sourceA = integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));
        SerialSource sourceB = integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-2"));
        SerialSourcePort port = integration.serialPortsGet(PORT);
        assertEquals(2, port.getConnectedSources().size());

        sourceA.closePort();
        assertEquals("摘一条少一条（owner 化不改变引用计数）", 1, port.getConnectedSources().size());
        assertEquals("剩余 owner 集合同步收缩",
                1, integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT)).size());

        sourceB.closePort();
        assertNull("末源注销才拆港（serialPorts 条目移除）", integration.serialPortsGet(PORT));
    }

    /** LEGACY 与类型化 owner 混存：identity 串直接构造的视图（读面回落包装）与注册 owner 同口共存。 */
    @Test
    public void legacyAndTypedOwnersCoexist() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        // identity 串视图只能经直接构造触达（公开注册入口已物理删除串形态）——账本读面
        // 对 owner=null 的视图按原串包 LEGACY owner，容忍语义保持
        new SerialSource(integration.serialPortsGet(PORT), "legacy-identity");
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));

        List<ResourceOwner> owners =
                integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
        assertEquals("LEGACY 与 DEVICE 各一条", 2, owners.size());
        boolean hasLegacy = false;
        boolean hasDevice = false;
        for (ResourceOwner owner : owners) {
            hasLegacy |= owner.getLevel() == OwnerLevel.LEGACY;
            hasDevice |= owner.getLevel() == OwnerLevel.DEVICE;
        }
        assertTrue("LEGACY 条目在账", hasLegacy);
        assertTrue("DEVICE 条目在账", hasDevice);
    }

    /** 同设备重注册同键：connectedSources 计数 +1（每注册方一视图），owner 集合按 ownerKey 去重。 */
    @Test
    public void sameDeviceReRegisterDedupsOwnerKey() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));
        SerialSourcePort port = integration.serialPortsGet(PORT);

        assertEquals("每注册方一个视图（引用计数照加）", 2, port.getConnectedSources().size());
        assertEquals("owner 集合按 ownerKey 去重（identity 键不变量）",
                1, integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT)).size());
    }

    // ==================== §9-10：get*Info 精准查询 ====================

    /** 精准命中：设备身份三元组 → 该设备注册的 SerialInfo（账本持有，非调用方副本）。 */
    @Test
    public void getDeviceInfoPreciseMatchReturnsRegisteredInfo() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));

        SerialInfo found = integration.getDeviceInfo(COORD, "entry-1", "dev-1");
        assertNotNull(found);
        assertEquals(PORT, found.portName);
        assertEquals("返回注册 Info 活引用（RECONFIGURE 原位替换后同源）",
                integration.serialPortsGet(PORT).serialInfo, found);
    }

    /** 未注册身份如实 null（不做旗下罗列/推断）。 */
    @Test
    public void getDeviceInfoUnregisteredReturnsNull() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));

        assertNull(integration.getDeviceInfo(COORD, "entry-1", "dev-unknown"));
        assertNull(integration.getDeviceInfo(COORD, "entry-unknown", "dev-1"));
        assertNull(integration.getDeviceInfo("com.ecat:integration-unknown", "entry-1", "dev-1"));
    }

    /** 一名多资源=异常形态：同设备身份注册两口，精准查询明确异常不猜。 */
    @Test
    public void getDeviceInfoOneIdentityTwoPortsThrows() {
        SerialIntegration integration = new SerialIntegration();
        ResourceOwner owner = deviceOwner("entry-1", "dev-1");
        // 端口名非真实路径：mock 串口注入（isOpen=true 短路 openPort，不触 getCommPort）
        integration.serialPortsPutForTest("PORT-A", mockOpenPort(integration, "PORT-A"));
        integration.serialPortsPutForTest("PORT-B", mockOpenPort(integration, "PORT-B"));
        new SerialSource(integration.serialPortsGet("PORT-A"), owner);
        new SerialSource(integration.serialPortsGet("PORT-B"), owner);

        try {
            integration.getDeviceInfo(COORD, "entry-1", "dev-1");
            fail("一名多资源应明确抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 严格模式：不猜不取首笔
        }
    }

    /** mock 串口开路端口（端口名可为任意键，绕过 getCommPort 真实路径校验）。 */
    private static SerialSourcePort mockOpenPort(SerialIntegration integration, String portName) {
        SerialSourcePort port = new SerialSourcePort(info(portName, 500), 1, integration);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        port.serialPort = serialPort;
        return port;
    }

    /** ENTRY/INTEGRATION 层各查自己（层级精准匹配；设备注册不算 entry 本体注册）。 */
    @Test
    public void entryAndIntegrationLevelPreciseQueries() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));
        integration.register(info(PORT, 500), ResourceOwner.entry(COORD, "entry-2"));
        integration.register(info(PORT, 500), ResourceOwner.integration(COORD));

        assertNotNull(integration.getEntryInfo(COORD, "entry-2"));
        assertNull("DEVICE 注册不冒充 entry 本体注册（层级精准）",
                integration.getEntryInfo(COORD, "entry-1"));
        assertNotNull(integration.getIntegrationInfo(COORD));
        assertNull(integration.getIntegrationInfo("com.ecat:integration-other"));
    }

    /** RECONFIGURE 后查询返回活配置（timeout 原位替换进同一 port.serialInfo）。 */
    @Test
    public void getDeviceInfoReturnsLiveConfigAfterReconfigure() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));

        integration.register(info(PORT, 2000), deviceOwner("entry-1", "dev-1"));

        SerialInfo found = integration.getDeviceInfo(COORD, "entry-1", "dev-1");
        assertNotNull(found);
        assertEquals("RECONFIGURE 原位替换后查询返回活配置（非调用方旧副本）", 2000, found.timeout);
    }

    // ==================== get*Owners 折叠 ====================

    /** 折叠语义：device→entry 去重、entry/integration→integration 去重；LEGACY 无层可折原样透传。 */
    @Test
    public void ownerFoldingByLevel() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-1"));
        integration.register(info(PORT, 500), deviceOwner("entry-1", "dev-2"));
        integration.register(info(PORT, 500), deviceOwner("entry-2", "dev-3"));
        new SerialSource(integration.serialPortsGet(PORT), "legacy-identity");
        ResourceRef ref = new ResourceRef(ResourceKind.SERIAL_PORT, PORT);

        List<ResourceOwner> deviceOwners = integration.getDeviceOwners(ref);
        assertEquals("全量 4 条（3 DEVICE + 1 LEGACY）", 4, deviceOwners.size());

        List<ResourceOwner> entryOwners = integration.getEntryOwners(ref);
        assertEquals("device 折叠到 entry 去重 2 条 + LEGACY 原样透传 1 条", 3, entryOwners.size());
        for (ResourceOwner owner : entryOwners) {
            if (owner.getLevel() == OwnerLevel.ENTRY) {
                assertTrue("entryId 归属 entry-1/entry-2 之一",
                        "entry-1".equals(owner.getEntryId()) || "entry-2".equals(owner.getEntryId()));
            }
        }

        List<ResourceOwner> integrationOwners = integration.getIntegrationOwners(ref);
        assertEquals("折叠到集成：1 条 INTEGRATION + LEGACY 透传", 2, integrationOwners.size());
    }

    /** refKind 守卫：非 SERIAL_PORT 的 ref 属调用方错误，明确异常不猜。 */
    @Test
    public void wrongResourceKindRejected() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        ResourceRef wrong = new ResourceRef(ResourceKind.MODBUS_CONNECTION, PORT);
        try {
            integration.getDeviceOwners(wrong);
            fail("非 SERIAL_PORT ref 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 严格模式
        }
    }

    // ==================== register(info, host) 三合一 ====================

    /** 设备宿主注册：owner 从 host 派生（DEVICE 层、deviceId=getId()），宿主收尾自动摘账。 */
    @Test
    public void registerWithDeviceHostDerivesOwnerAndBindsLifecycle() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        HostDevice device = new HostDevice("entry-9");

        SerialSource source = integration.register(info(PORT, 500), device);
        assertNotNull(source);
        List<ResourceOwner> owners =
                integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
        assertEquals(1, owners.size());
        ResourceOwner owner = owners.get(0);
        assertEquals("宿主派生 DEVICE 层", OwnerLevel.DEVICE, owner.getLevel());
        assertEquals("deviceId=getId() 稳定 UUID（四层身份模型）", device.getId(), owner.getDeviceId());
        assertEquals("entryId=getEntry().getEntryId() 真实主键", "entry-9", owner.getEntryId());
        assertEquals(COORD, owner.getCoordinate());

        // 宿主收尾（LIFO sweep）→ 自动摘账 → 末源拆港
        device.cancelManagedTasks();
        assertNull("宿主收尾自动摘账，末源拆港", integration.serialPortsGet(PORT));
    }

    /** 终态守卫：宿主已 sweep 后注册属病态调用——拒绝并就地回收（不留半活资源）。 */
    @Test
    public void registerWithTerminalHostRejectedAndReclaimed() {
        SerialIntegration integration = new SerialIntegration();
        HostDevice device = new HostDevice("entry-9");
        device.cancelManagedTasks(); // 宿主已终态

        try {
            integration.register(info(PORT, 500), device);
            fail("终态宿主注册应抛 RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
            // EasyHttpClient 同型守卫
        }
        assertNull("就地回收：不留半活端口条目", integration.serialPortsGet(PORT));
    }

    /** owner 重载（库间带主转发形态）：ADAPTER 条目合法入账，与 DIRECT 条目区分。 */
    @Test
    public void registerWithOwnerOverloadAcceptsAdapterEntries() {
        SerialIntegration integration = integrationWithPort(PORT, info(PORT, 500));
        ResourceOwner adapter = ResourceOwner.device(COORD, "entry-1", "dev-1").asAdapter();
        integration.register(info(PORT, 500), adapter);
        integration.register(info(PORT, 500), deviceOwner("entry-2", "dev-2"));

        List<ResourceOwner> owners =
                integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
        assertEquals("ADAPTER 与 DIRECT 各一条（同视图多设备条目合法）", 2, owners.size());
    }
}
