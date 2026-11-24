package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SerialCommSettings（comm_settings 类型化读端）单元测试：
 * 扁平 7 字段形状映射、枚举→jSerialComm 常量换算（含 TWO_STOP_BITS=3 陷阱、
 * parity title-case 值域）、timeout/flow_control 缺省同源、浮点字符串容忍、严格抛错。
 */
public class SerialCommSettingsTest {

    // ==================== 全形状映射 ====================

    @Test
    public void fullShape_mapsAllSevenFields() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_port", "/dev/ttyUSB0");
        comm.put("baudrate", 9600);
        comm.put("data_bits", 8);
        comm.put("stop_bits", "2");
        comm.put("parity", "Even");
        comm.put("flow_control", "17");
        comm.put("timeout", 1500);
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("comm_settings", comm);

        SerialInfo info = SerialCommSettings.parse(entryData);
        assertEquals("/dev/ttyUSB0", info.portName);
        assertEquals(9600, info.baudrate.intValue());
        assertEquals(8, info.dataBits.intValue());
        assertEquals("stop_bits \"2\" 必须映射 TWO_STOP_BITS=3（2 是 1.5 位，直传会静默错位）",
                SerialPort.TWO_STOP_BITS, info.stopBits.intValue());
        assertEquals(SerialPort.EVEN_PARITY, info.parity.intValue());
        assertEquals(17, info.flowControl);
        assertEquals("timeout 字段存在时必须真实传入（7 参构造器，不再丢弃）", 1500, info.timeout);
    }

    @Test
    public void stopBitsOne_mapsOneStopBit() {
        Map<String, Object> entryData = entryWith("stop_bits", "1");
        SerialInfo info = SerialCommSettings.parse(entryData);
        assertEquals(SerialPort.ONE_STOP_BIT, info.stopBits.intValue());
    }

    @Test
    public void parityTitleCaseValues_mapToJSerialCommConstants() {
        // serial 库 schema 枚举值是 title case（"None"/"Odd"/"Even"），与 modbus 库不同源
        assertEquals(SerialPort.NO_PARITY, SerialCommSettings.parse(entryWith("parity", "None")).parity.intValue());
        assertEquals(SerialPort.ODD_PARITY, SerialCommSettings.parse(entryWith("parity", "Odd")).parity.intValue());
        assertEquals(SerialPort.EVEN_PARITY, SerialCommSettings.parse(entryWith("parity", "Even")).parity.intValue());
    }

    // ==================== 可选字段缺省与 schema 预填同源 ====================

    @Test
    public void missingTimeout_fallsBackToSchemaPrefillSource() {
        SerialInfo info = SerialCommSettings.parse(entryWith("timeout", null));
        assertEquals("缺 timeout 回退 Const.READ_TIMEOUT_MS（与 SerialCommConfigSchema 预填同源）",
                Const.READ_TIMEOUT_MS.intValue(), info.timeout);
    }

    @Test
    public void missingFlowControl_defaultsToNone() {
        SerialInfo info = SerialCommSettings.parse(entryWith("flow_control", null));
        assertEquals("缺 flow_control 回退 0（无流控，与 schema 默认 NONE 同源）", 0, info.flowControl);
    }

    // ==================== 数值解析 ====================

    @Test
    public void stringNumbers_tolerateFloatStrings_fromApiConsumers() {
        // schema defaultValue 数值型经 API 消费方字符串化（"9600.0"）——Integer.parseInt 不认浮点
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_port", "/dev/ttyUSB1");
        comm.put("baudrate", "9600.0");
        comm.put("data_bits", 8.0);
        comm.put("stop_bits", "1");
        comm.put("parity", "Odd");
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("comm_settings", comm);

        SerialInfo info = SerialCommSettings.parse(entryData);
        assertEquals(9600, info.baudrate.intValue());
        assertEquals(8, info.dataBits.intValue());
        assertEquals(SerialPort.ODD_PARITY, info.parity.intValue());
    }

    // ==================== 严格模式：缺失/未知值抛带字段名的异常 ====================

    @Test
    public void missingCommSettings_throws() {
        try {
            SerialCommSettings.parse(new HashMap<>());
            fail("缺 comm_settings 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("comm_settings"));
        }
    }

    @Test
    public void missingSerialPort_throwsWithFieldName() {
        Map<String, Object> comm = baseComm();
        comm.remove("serial_port");
        try {
            SerialCommSettings.parse(wrap(comm));
            fail("缺 serial_port 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("serial_port"));
        }
    }

    @Test
    public void missingBaudrate_throwsWithFieldName() {
        Map<String, Object> comm = baseComm();
        comm.remove("baudrate");
        try {
            SerialCommSettings.parse(wrap(comm));
            fail("缺 baudrate 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("baudrate"));
        }
    }

    @Test
    public void unknownParity_throwsStrictly_noSilentFallback() {
        try {
            SerialCommSettings.parse(entryWith("parity", "WHATEVER"));
            fail("未知校验位应严格抛（fail-loud 优于静默兜底 NO_PARITY）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("parity"));
        }
    }

    @Test
    public void unknownStopBits_throwsStrictly() {
        try {
            SerialCommSettings.parse(entryWith("stop_bits", "1.5"));
            fail("未知停止位应严格抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("stop_bits"));
        }
    }

    @Test
    public void wholeNumberFloatForms_normalizeToEnumValues() {
        // 数值编码枚举（stop_bits/flow_control）容忍整数值的浮点形态（"1.0"→"1"）——
        // gassensor bug-record-20260829-014500 的既有回归面（API 消费方字符串化），表示容忍非取值容忍
        SerialInfo stopBitsFloat = SerialCommSettings.parse(entryWith("stop_bits", "1.0"));
        assertEquals(SerialPort.ONE_STOP_BIT, stopBitsFloat.stopBits.intValue());
        SerialInfo flowControlFloat = SerialCommSettings.parse(entryWith("flow_control", "17.0"));
        assertEquals("flow_control 浮点形态同样规范化", 17, flowControlFloat.flowControl);
    }

    @Test
    public void fractionalStopBits_throwStrictly_noSilentRounding() {
        // "1.5" 是真实 UART 档位——静默 Math.round 取整成 2 会错位，必须严格抛
        try {
            SerialCommSettings.parse(entryWith("stop_bits", "1.5"));
            fail("非整数停止位应严格抛，不得静默取整");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("stop_bits"));
        }
    }

    @Test
    public void unknownFlowControl_throwsStrictly() {
        try {
            SerialCommSettings.parse(entryWith("flow_control", "99"));
            fail("未知流控值应严格抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("flow_control"));
        }
    }

    @Test
    public void nonNumericBaudrate_throws() {
        try {
            SerialCommSettings.parse(entryWith("baudrate", "abc"));
            fail("非数值 baudrate 应抛");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("baudrate"));
        }
    }

    // ==================== TCP 档（transport=tcp：host/port 三字段，UART 忽略） ====================

    @Test
    public void tcpTransport_parsesHostPortAndSkipsUartFields() {
        // TCP 档不提交任何 UART 字段：无本地串口的主机形态（串口服务器硬件侧持有 UART 参数）
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("transport", "tcp");
        comm.put("host", "192.168.1.50");
        comm.put("port", 4001);
        comm.put("timeout", 800);
        SerialInfo info = SerialCommSettings.parse(wrap(comm));

        assertEquals("portName 填 host:port（注册表键复用）", "192.168.1.50:4001", info.portName);
        assertEquals(SerialTransport.TCP, info.transport);
        assertEquals("192.168.1.50", info.tcpHost);
        assertEquals(4001, info.tcpPort.intValue());
        assertEquals("timeout 两档通用", 800, info.timeout);
        assertEquals("UART 参数本机不持有", null, info.baudrate);
    }

    @Test
    public void tcpTransport_toleratesStaleUartFields_byIgnoring() {
        // TCP 档 UI 全显形态下存量/误填的 UART 值一律忽略（严格=不读，非静默乱用）
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("transport", "tcp");
        comm.put("host", "gw.local");
        comm.put("port", "8899");
        comm.put("baudrate", 9600);
        comm.put("parity", "Odd");
        SerialInfo info = SerialCommSettings.parse(wrap(comm));
        assertEquals("gw.local:8899", info.portName);
        assertEquals(8899, info.tcpPort.intValue());
        assertEquals(null, info.baudrate);
    }

    @Test
    public void missingTransport_defaultsToSerial() {
        assertEquals("transport 键缺失=SERIAL（schema 默认同源，存量 entry 零迁移）",
                SerialTransport.SERIAL, SerialCommSettings.parse(entryWith("stop_bits", "1")).transport);
    }

    @Test
    public void tcpTransport_missingHost_throwsWithFieldName() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("transport", "tcp");
        comm.put("port", 4001);
        try {
            SerialCommSettings.parse(wrap(comm));
            fail("TCP 档缺 host 应抛（TcpCommSettings 严格读缺字段带名抛）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("host"));
        }
    }

    @Test
    public void tcpTransport_outOfRangePort_throws() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("transport", "tcp");
        comm.put("host", "gw.local");
        comm.put("port", 70000);
        try {
            SerialCommSettings.parse(wrap(comm));
            fail("port 越界应抛（TcpConfig.build 端口域校验随复用继承）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("65535"));
        }
    }

    @Test
    public void unknownTransportValue_throwsStrictly() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("transport", "bogus");
        comm.put("host", "gw.local");
        comm.put("port", 4001);
        try {
            SerialCommSettings.parse(wrap(comm));
            fail("未知 transport 取值应严格抛（不静默回退 SERIAL）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("transport"));
        }
    }

    // ==================== 辅助 ====================

    /** 构造最小合法 comm_settings（可选字段不填），可注入单个覆盖字段。 */
    private static Map<String, Object> baseComm() {
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_port", "/dev/ttyUSB0");
        comm.put("baudrate", 9600);
        comm.put("data_bits", 8);
        comm.put("stop_bits", "1");
        comm.put("parity", "None");
        return comm;
    }

    private static Map<String, Object> wrap(Map<String, Object> comm) {
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("comm_settings", comm);
        return entryData;
    }

    /** 基础 entry + 顶层 comm_settings 内覆盖单字段（value=null 表示移除该字段）。 */
    private static Map<String, Object> entryWith(String overrideKey, Object overrideValue) {
        Map<String, Object> comm = baseComm();
        if (overrideValue == null) {
            comm.remove(overrideKey);
        } else {
            comm.put(overrideKey, overrideValue);
        }
        return wrap(comm);
    }
}
