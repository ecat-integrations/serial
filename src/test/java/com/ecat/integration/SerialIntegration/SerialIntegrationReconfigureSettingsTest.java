package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;

/**
 * 【RED：F-34 RECONFIGURE 参数静默不生效】bug-record-20260826-001300：
 * RECONFIGURE 改 comm 参数（timeout/baudrate 等）后设备重 load，register() 命中同口
 * 复用路径——timeout 不在 settingsMatch 比较集内被静默吞掉；物理参数变化则抛
 * IllegalArgumentException，均需 disable/enable 兜底重建端口才生效。
 *
 * <p>契约（修复后）：
 * <ul>
 *   <li>timeout-only 变化：纯软件参数（getTimeout 读 serialInfo，不下发 OS），
 *       原位替换 SerialInfo，<b>不</b>重建端口（避免无谓 churn）；</li>
 *   <li>物理参数（baudrate/dataBits/stopBits/parity/flowControl）变化：
 *       复用 disable/enable 同款重建路径 close+reopen，新参数即时生效；</li>
 *   <li>参数完全一致：复用既有端口对象，不 close 不重建（回归护栏）。</li>
 * </ul>
 */
public class SerialIntegrationReconfigureSettingsTest {

    /**
     * 测试端口名用 /dev/null：jSerialComm getCommPort 对不存在的路径抛
     * SerialPortInvalidPortException，对存在文件返回对象（openPort 失败仅返回 false，不抛），
     * 使 reopen 路径在无真实串口的 CI 环境可走到。
     */
    private static final String PORT = "/dev/null";

    private SerialIntegration newIntegrationWithOpenPort(SerialInfo info) {
        SerialIntegration integration = new SerialIntegration();
        // 直接构造共享端口并注入 mock 串口（绕过 register→openPort→getCommPort 对假口名抛
        // SerialPortInvalidPortException），使 closePort/reopen 行为可 verify
        SerialSourcePort port = new SerialSourcePort(info, 1, integration);
        SerialPort serialPort = mock(SerialPort.class);
        // isOpen 跟随 closePort 翻转：openPort 的 already-open 短路与重建路径都依赖 isOpen，
        // 恒 true 会让 reopen 短路在旧 mock 上
        final boolean[] open = {true};
        when(serialPort.isOpen()).thenAnswer(inv -> open[0]);
        doAnswer(inv -> {
            open[0] = false;
            return true;
        }).when(serialPort).closePort();
        port.serialPort = serialPort;
        integration.serialPortsPutForTest(PORT, port);
        return integration;
    }

    /** 红测：RECONFIGURE 改 timeout，同口注册后端口读到的 timeout 必须是新值（现状静默保留旧 500）。 */
    @Test
    public void timeoutChangeTakesEffectWithoutPortReopen() {
        SerialIntegration integration = newIntegrationWithOpenPort(
                new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500));
        SerialSourcePort port = integration.serialPortsGet(PORT);
        SerialPort originalSerialPort = port.serialPort;

        integration.register(new SerialInfo(PORT, 9600, 8, 1, 0, 0, 2000), "dev-1");

        assertEquals("RECONFIGURE 改 timeout 后 getTimeout 必须返回新值", 2000, port.getTimeout());
        // timeout 是纯软件参数：不得 close 物理端口（无谓 churn）
        verify(originalSerialPort, never()).closePort();
        assertSame("timeout-only 变化不得重建端口对象", originalSerialPort, port.serialPort);
    }

    /** 红测：RECONFIGURE 改 baudrate（物理参数），必须 close+reopen 重建端口（现状抛异常逼 disable/enable）。 */
    @Test
    public void physicalSettingsChangeRebuildsPort() {
        SerialIntegration integration = newIntegrationWithOpenPort(
                new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500));
        SerialSourcePort port = integration.serialPortsGet(PORT);
        SerialPort originalSerialPort = port.serialPort;

        integration.register(new SerialInfo(PORT, 19200, 8, 1, 0, 0, 500), "dev-1");

        verify(originalSerialPort).closePort(); // 物理参数变化必须 close 旧端口（disable/enable 同款重建路径）
        assertEquals("重建后端口必须持有新 baudrate", Integer.valueOf(19200), port.serialInfo.baudrate);
        assertNotSameWithMockReopen(port, originalSerialPort);
    }

    /** 回归：参数完全一致时复用既有端口，不 close 不重建（避免无谓 churn）。 */
    @Test
    public void unchangedSettingsReusePortWithoutRebuild() {
        SerialIntegration integration = newIntegrationWithOpenPort(
                new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500));
        SerialSourcePort port = integration.serialPortsGet(PORT);
        SerialPort originalSerialPort = port.serialPort;

        integration.register(new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500), "dev-2");

        assertSame("参数未变必须复用同一 SerialSourcePort", port, integration.serialPortsGet(PORT));
        verify(originalSerialPort, never()).closePort();
        assertSame("参数未变不得重开端口", originalSerialPort, port.serialPort);
    }

    /** openPort 用 getCommPort 产新 SerialPort 对象（测试环境假口 open 失败但对象已换新），证明走了 reopen。 */
    private static void assertNotSameWithMockReopen(SerialSourcePort port, SerialPort original) {
        assertNotNull(port.serialPort);
        // openPort 会 new 出真实 SerialPort 替换 mock——只要不再引用旧 mock 即证明重建路径已执行
        if (port.serialPort == original) {
            throw new AssertionError("物理参数变化后必须 reopen（serialPort 不得仍是旧对象）");
        }
    }
}
