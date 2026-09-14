package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceKind;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.ResourceRef;
import com.ecat.core.Device.RemovalHost;

/**
 * registerForDevice 消费方一步得源（io-resource-owner 设计 §4 ResourceQuery 契约 + §8 B7）：
 * 身份与生命周期两维度正交——解析键=设备身份三元组（账本精准匹配），追加登记=消费方自己的
 * owner（独立注册方身份），生命周期=宿主锚点 host（onRemove 摘账 + 终态守卫）。
 * 未注册如实 null 不另开资源（杜绝凭旧 Info 开新口的半死挂靠）。
 */
public class SerialRegisterForDeviceTest {

    private static final String COORD = "com.ecat:integration-register-device-test";
    private static final String PORT = "/dev/null";

    /** 收集型假宿主：记录 onRemove 动作供测试手动触发收尾。 */
    private static final class RecordingHost implements RemovalHost {
        final List<Runnable> actions = new ArrayList<>();

        @Override
        public void onRemove(Runnable action) {
            actions.add(action);
        }
    }

    /** 终态假宿主：onRemove 恒抛（宿主已 sweep 形态）。 */
    private static final class TerminalHost implements RemovalHost {
        @Override
        public void onRemove(Runnable action) {
            throw new RejectedExecutionException("宿主已终态");
        }
    }

    private static SerialIntegration integrationWithDevicePort() {
        SerialIntegration integration = new SerialIntegration();
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(PORT, 9600, 8, 1, 0), 1, integration);
        SerialPort serialPort = mock(SerialPort.class);
        final boolean[] open = {true};
        when(serialPort.isOpen()).thenAnswer(inv -> open[0]);
        doAnswer(inv -> {
            open[0] = false;
            return true;
        }).when(serialPort).closePort();
        port.serialPort = serialPort;
        integration.serialPortsPutForTest(PORT, port);
        // 设备本体注册（DEVICE 层 owner 建账）
        integration.register(new SerialInfo(PORT, 9600, 8, 1, 0),
                ResourceOwner.device(COORD, "entry-1", "dev-1"));
        return integration;
    }

    /** 命中：返回挂靠视图（同口常住人口 +1），消费方 owner 入账，宿主绑定登记。 */
    @Test
    public void registerForDeviceHitAppendsConsumerOwnerAndBindsHost() {
        SerialIntegration integration = integrationWithDevicePort();
        SerialSourcePort port = integration.serialPortsGet(PORT);
        int base = port.getConnectedSources().size();
        RecordingHost host = new RecordingHost();
        ResourceOwner consumer = ResourceOwner.entry(COORD, "entry-adm");

        SerialSource source = integration.registerForDevice(
                COORD, "entry-1", "dev-1", consumer, host);

        assertNotNull(source);
        assertEquals("常住人口 +1（同键挂靠共享资源，非新开资源）", base + 1,
                port.getConnectedSources().size());
        List<ResourceOwner> owners =
                integration.getDeviceOwners(new ResourceRef(ResourceKind.SERIAL_PORT, PORT));
        boolean consumerInLedger = false;
        for (ResourceOwner owner : owners) {
            consumerInLedger |= "entry-adm".equals(owner.getEntryId())
                    && owner.getLevel() == OwnerLevel.ENTRY;
        }
        assertEquals("消费方 owner 入账", 1, host.actions.size());
        assertNotNull("返回视图挂靠目标端口", source.getPortName());
        assertTrue("消费方 owner 入账（entry-adm 的 ENTRY 条目）", consumerInLedger);
    }

    /** 未注册身份：如实 null，不凭 Info 另开资源（serialPorts 零新增）。 */
    @Test
    public void registerForDeviceUnknownDeviceReturnsNullWithoutNewResource() {
        SerialIntegration integration = integrationWithDevicePort();
        SerialSourcePort port = integration.serialPortsGet(PORT);
        int base = port.getConnectedSources().size();

        SerialSource source = integration.registerForDevice(
                COORD, "entry-1", "dev-unknown", ResourceOwner.entry(COORD, "entry-adm"),
                new RecordingHost());

        assertNull("未注册设备如实 null（不猜不开）", source);
        assertEquals("零新增源（不另开资源）", base, port.getConnectedSources().size());
    }

    /** 终态宿主：拒绝并就地回收（新增源摘回，账本回到注册前形态）。 */
    @Test
    public void registerForDeviceTerminalHostRejectedAndReclaimed() {
        SerialIntegration integration = integrationWithDevicePort();
        SerialSourcePort port = integration.serialPortsGet(PORT);
        int base = port.getConnectedSources().size();

        try {
            integration.registerForDevice(COORD, "entry-1", "dev-1",
                    ResourceOwner.entry(COORD, "entry-adm"), new TerminalHost());
            fail("终态宿主应抛 RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
            // EasyHttpClient 同型守卫
        }
        assertEquals("就地回收：新增源摘回", base, port.getConnectedSources().size());
    }

    /** 宿主收尾自动摘账：触发 onRemove 动作 → 消费方源注销，设备本体不受影响。 */
    @Test
    public void hostRemovalActionUnregistersConsumerSourceOnly() {
        SerialIntegration integration = integrationWithDevicePort();
        SerialSourcePort port = integration.serialPortsGet(PORT);
        int base = port.getConnectedSources().size();
        RecordingHost host = new RecordingHost();
        integration.registerForDevice(COORD, "entry-1", "dev-1",
                ResourceOwner.entry(COORD, "entry-adm"), host);
        assertEquals(base + 1, port.getConnectedSources().size());

        host.actions.forEach(Runnable::run);

        assertEquals("消费方源自动摘账", base, port.getConnectedSources().size());
        assertNotNull("设备本体注册不受影响（口仍在账）", integration.serialPortsGet(PORT));
    }
}
