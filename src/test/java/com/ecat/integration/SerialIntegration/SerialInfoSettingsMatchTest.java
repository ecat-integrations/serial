package com.ecat.integration.SerialIntegration;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SerialInfo#settingsMatch} 按传输通道分档的重建判据测试：
 * SERIAL 档语义不变（五参数+流控，timeout 不比）；TCP 档只比 host/port（UART 参数在
 * 串口服务器硬件侧，本机不持有）；跨档必不匹配（reconfigure 判据的档案位）。
 *
 * @author coffee
 */
public class SerialInfoSettingsMatchTest {

    private static SerialInfo serial(String port, int baudrate) {
        return new SerialInfo(port, baudrate, 8, 1, 0);
    }

    private static SerialInfo tcp(String host, int port) {
        SerialInfo info = new SerialInfo(host + ":" + port, null, null, null, null);
        info.transport = SerialTransport.TCP;
        info.tcpHost = host;
        info.tcpPort = port;
        return info;
    }

    // ==================== SERIAL 档（既有语义保持） ====================

    @Test
    public void serialMatch_samePhysicalParams_true() {
        assertTrue(serial("/dev/ttyUSB0", 9600).settingsMatch(serial("/dev/ttyUSB0", 9600)));
    }

    @Test
    public void serialMatch_baudrateChanged_false() {
        assertFalse(serial("/dev/ttyUSB0", 9600).settingsMatch(serial("/dev/ttyUSB0", 115200)));
    }

    @Test
    public void serialMatch_timeoutDifference_stillMatches() {
        // timeout 纯软件参数（不下发 OS）：既有语义=不参与匹配
        SerialInfo a = serial("/dev/ttyUSB0", 9600);
        SerialInfo b = serial("/dev/ttyUSB0", 9600);
        b.timeout = 3000;
        assertTrue(a.settingsMatch(b));
    }

    // ==================== TCP 档（只比 host/port） ====================

    @Test
    public void tcpMatch_sameHostPort_true() {
        assertTrue(tcp("gw.local", 4001).settingsMatch(tcp("gw.local", 4001)));
    }

    @Test
    public void tcpMatch_portChanged_false_rebuildCriterion() {
        // reconfigure 重建判据：TCP 档 = host/port 变化才重建连接
        assertFalse(tcp("gw.local", 4001).settingsMatch(tcp("gw.local", 8899)));
    }

    @Test
    public void tcpMatch_hostChanged_false_rebuildCriterion() {
        assertFalse(tcp("gw.local", 4001).settingsMatch(tcp("10.0.0.9", 4001)));
    }

    @Test
    public void tcpMatch_timeoutDifference_stillMatches() {
        SerialInfo a = tcp("gw.local", 4001);
        SerialInfo b = tcp("gw.local", 4001);
        b.timeout = 3000;
        assertTrue(a.settingsMatch(b));
    }

    // ==================== 跨档与空值 ====================

    @Test
    public void crossTransport_neverMatches() {
        assertFalse(tcp("x", 1).settingsMatch(serial("x", 9600)));
        assertFalse(serial("/dev/ttyUSB0", 9600).settingsMatch(tcp("/dev/ttyUSB0", 1)));
    }

    @Test
    public void nullOther_false() {
        assertFalse(serial("/dev/ttyUSB0", 9600).settingsMatch(null));
        assertFalse(tcp("gw.local", 4001).settingsMatch(null));
    }
}
