package com.ecat.integration.SerialIntegration.ConfigFlows;

import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.integration.SerialIntegration.ConfigSchemas.BaudRate;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;
import com.ecat.integration.SerialIntegration.Const;
import com.ecat.integration.SerialIntegration.SerialTransport;
import com.ecat.integration.TcpIntegration.ConfigSchemas.TcpClientCommConfigSchema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * SerialCommFlow（串口通讯子 flow，两步形态）单元测试：
 * 挂载走读（入口=transport_select → comm_config → 出口交回宿主尾步）、步序与分档表单
 * （serial 档 7 UART 字段 / tcp 档整块复用 tcp schema）、落盘形状
 * （comm_settings = {transport, …该档字段…}）、校验失败回显、定制形态对照
 * （静态 schema / defaults / defaultsTcp 三形态与优先级契约）。
 */
public class SerialCommFlowTest {

    @BeforeClass
    public static void injectTestSerialPort() {
        // serial_port 是动态枚举（校验成员资格），测试环境无真实串口——注入虚拟端口
        Map<String, String> ports = new LinkedHashMap<>();
        ports.put("/dev/ttyUSB0", "/dev/ttyUSB0");
        SerialCommConfigSchema.setTestPortSupplier(() -> ports);
    }

    @AfterClass
    public static void clearTestSerialPort() {
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    /** 测试宿主：user 入口 + device_config + final_confirm，挂载被测子 flow，device_config 末尾进入。 */
    private static class HostFlow extends AbstractConfigFlow {

        final String commStepId;

        HostFlow(SerialCommFlow commFlow) {
            super();
            registerStepUser("user", "配置设备", this::stepUser);
            registerStep("device_config", this::stepDeviceConfig, "设备配置");
            registerStep("final_confirm", this::stepFinalConfirm, "确认配置");
            commStepId = registerFlowStep(commFlow, "final_confirm");
        }

        private ConfigFlowResult stepUser(Map<String, Object> userInput, FlowContext ctx) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("user", new ConfigSchema(), new HashMap<>());
            }
            return showForm("device_config", new ConfigSchema(), new HashMap<>());
        }

        private ConfigFlowResult stepDeviceConfig(Map<String, Object> userInput) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("device_config", new ConfigSchema(), new HashMap<>());
            }
            context.getEntryData().putAll(userInput);
            return handleStep(commStepId, null);
        }

        private ConfigFlowResult stepFinalConfirm(Map<String, Object> userInput) {
            if (userInput == null || userInput.isEmpty()) {
                return showForm("final_confirm", new ConfigSchema(), new HashMap<>());
            }
            return createEntry();
        }
    }

    /** 驱动宿主直到子 flow 首屏（入口步 transport_select）显示。 */
    private static HostFlow enterSubFlow(SerialCommFlow commFlow) {
        HostFlow host = new HostFlow(commFlow);
        host.executeUserStep(null);
        host.handleStep("user", input("k", "v"));
        ConfigFlowResult r = host.handleStep("device_config", input("name", "dev"));
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, r.getType());
        assertEquals("宿主 device_config 提交后应进子 flow 入口屏（transport_select）",
                "transport_select", r.getStepId());
        return host;
    }

    /** 提交通道选择并断言步序（transport_select → comm_config）。 */
    private static ConfigFlowResult selectTransport(HostFlow host, String transport) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("transport", transport);
        ConfigFlowResult r = host.handleStep("transport_select", choice);
        assertEquals("通道选择提交后应进 comm_config（errors=" + r.getErrors() + "）",
                "comm_config", r.getStepId());
        return r;
    }

    // ==================== 挂载走读 ====================

    @Test
    public void submitCommConfig_exitsToHostTail_andPersistsMergedShape() {
        HostFlow host = enterSubFlow(new SerialCommFlow());

        selectTransport(host, "serial");
        Map<String, Object> commInput = commInput();
        ConfigFlowResult exit = host.handleStep("comm_config", commInput);
        assertEquals("子 flow 出口应交回宿主尾步（errors=" + exit.getErrors() + "）",
                ConfigFlowResult.ResultType.SHOW_FORM, exit.getType());
        assertEquals("提交合法通讯配置应落宿主尾步（errors=" + exit.getErrors() + "）",
                "final_confirm", exit.getStepId());
        assertEquals("final_confirm", host.getCurrentStep());

        // 落盘形状：comm_settings = {transport, …该档字段…}（transport_select 的选择并入前置）
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transport", "serial");
        expected.putAll(commInput);
        assertEquals(expected, host.getContext().getEntryData().get("comm_settings"));
        // 漫游面：子 flow 两步数据都进宿主 stepInputs（stepId 不变，reconfigure 漫游兼容）
        assertTrue(host.getContext().getStepInputs().containsKey("transport_select"));
        assertTrue(host.getContext().getStepInputs().containsKey("comm_config"));
    }

    @Test
    public void invalidInput_redisplaysCommConfigWithErrors() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "serial");

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("serial_port", "/dev/ttyUSB0");
        bad.put("baudrate", "99999");   // 非枚举成员
        bad.put("data_bits", "8");
        bad.put("stop_bits", "1");
        bad.put("parity", "None");
        ConfigFlowResult r = host.handleStep("comm_config", bad);
        assertEquals("校验失败应回显 comm_config", "comm_config", r.getStepId());
        assertNotNull("应携带校验错误", r.getErrors().get("baudrate"));
        // 未过校验不得落盘、不得出口
        assertFalse(host.getContext().getEntryData().containsKey("comm_settings"));
    }

    @Test
    public void firstScreen_isTransportSelect_withSerialPrefill() {
        // 入口屏=通道选择：transport 必选、options 与枚举一一对应、新建流程缺省预填 serial
        HostFlow host = enterSubFlow(new SerialCommFlow());
        ConfigFlowResult form = host.handleStep("transport_select", null);
        assertEquals("transport_select", form.getStepId());
        AbstractConfigItem<?> transport = field(form.getSchema(), "transport");
        assertNotNull("入口屏应含 transport 字段", transport);
        assertTrue("transport 应必选（两步形态的档位来源）", transport.isRequired());
        assertEquals("新建流程缺省预填 serial（与 parse 缺省同源）",
                "serial", transport.getDefaultValue());
        assertEquals("options 与 SerialTransport 枚举一一对应",
                SerialTransport.toMap(), ((EnumConfigItem) transport).getOptionLabels());
        assertNull("入口屏不得混入 comm 字段（两档字段不混一张表单）", field(form.getSchema(), "serial_port"));
    }

    @Test
    public void transportSelect_garbageValue_redisplaysWithError() {
        // 通道选择的成员资格执法在表单期（非法取值不得进 comm_config）
        HostFlow host = enterSubFlow(new SerialCommFlow());
        Map<String, Object> bogus = new LinkedHashMap<>();
        bogus.put("transport", "bogus");
        ConfigFlowResult r = host.handleStep("transport_select", bogus);
        assertEquals("非法通道取值应回显 transport_select", "transport_select", r.getStepId());
        assertNotNull("应携带 transport 错误", r.getErrors().get("transport"));
        assertFalse(host.getContext().getEntryData().containsKey("comm_settings"));
    }

    @Test
    public void commConfig_withoutTransportSelect_strictlyRejected() {
        // 越步直投 comm_config（stepInputs 无通道选择）= 两步形态的结构违约：严格抛出不猜档
        HostFlow host = enterSubFlow(new SerialCommFlow());
        try {
            host.handleStep("comm_config", commInput());
            fail("未提交 transport_select 直投 comm_config 必须严格拒绝");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("transport_select"));
        }
        assertFalse(host.getContext().getEntryData().containsKey("comm_settings"));
    }

    // ==================== serial 档 comm_config 表单（7 UART 字段） ====================

    @Test
    public void commConfigForm_serialProfile_showsSevenUartFieldsOnly() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "serial");
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("comm_config", form.getStepId());
        assertNotNull("表单应含 serial_port 字段", field(form.getSchema(), "serial_port"));
        assertNotNull("表单应含 baudrate 字段", field(form.getSchema(), "baudrate"));
        assertNotNull("表单应含 data_bits 字段", field(form.getSchema(), "data_bits"));
        assertNotNull("表单应含 stop_bits 字段", field(form.getSchema(), "stop_bits"));
        assertNotNull("表单应含 parity 字段", field(form.getSchema(), "parity"));
        assertNotNull("表单应含 flow_control 字段", field(form.getSchema(), "flow_control"));
        assertNotNull("表单应含 timeout 字段", field(form.getSchema(), "timeout"));
        assertEquals("serial 档表单应恰为 7 个 UART 字段", 7, form.getSchema().getFields().size());
        // 分档表单负断言：host/port 归 tcp 档，transport 归入口步——不得混入
        assertNull("serial 档表单不得含 host（tcp 档专属）", field(form.getSchema(), "host"));
        assertNull("serial 档表单不得含 port（tcp 档专属）", field(form.getSchema(), "port"));
        assertNull("serial 档表单不得含 transport（入口步已收集）", field(form.getSchema(), "transport"));
    }

    @Test
    public void serialSubmission_withoutTransportKey_stillExitsToHostTail() {
        // COM 档既有提交形态回归：UART 载荷不带 transport 键（旧表单/脚本形态）→ 原行为过闸，
        // transport 由 flow 并入落盘
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "serial");
        Map<String, Object> uartOnly = commInput();
        assertFalse(uartOnly.containsKey("transport"));
        ConfigFlowResult exit = host.handleStep("comm_config", uartOnly);
        assertEquals("final_confirm", exit.getStepId());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transport", "serial");
        expected.putAll(uartOnly);
        assertEquals(expected, host.getContext().getEntryData().get("comm_settings"));
    }

    // ==================== TCP 档（comm_config 整块复用 tcp schema） ====================

    @Test
    public void commConfigForm_tcpProfile_reusesTcpSchemaWhole() {
        // tcp 档渲染 TcpClientCommConfigSchema 整块：host/port 必填原生继承、
        // reconnect 字段保留（B1 重连分工修正后有意义）、不摘字段不设默认端口
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "tcp");
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("comm_config", form.getStepId());

        ConfigSchema expected = TcpClientCommConfigSchema.builder().build().createSchema();
        assertEquals("tcp 档表单字段集应与 tcp schema 整块一致",
                expected.getFields().size(), form.getSchema().getFields().size());
        AbstractConfigItem<?> hostField = field(form.getSchema(), "host");
        AbstractConfigItem<?> portField = field(form.getSchema(), "port");
        assertNotNull("tcp 档应含 host", hostField);
        assertNotNull("tcp 档应含 port", portField);
        assertTrue("host 必填（tcp schema 原生 required，不改）", hostField.isRequired());
        assertTrue("port 必填（tcp schema 原生 required，不改）", portField.isRequired());
        assertNotNull("reconnect 字段保留（重连参数 B1 后有意义）",
                field(form.getSchema(), "max_reconnect_attempts"));
        assertNotNull("reconnect_timeout 字段保留", field(form.getSchema(), "reconnect_timeout"));
        assertNull("tcp 档不得混入 UART 字段", field(form.getSchema(), "serial_port"));
        assertNull("tcp 档不得混入 transport", field(form.getSchema(), "transport"));
    }

    @Test
    public void tcpSubmission_withoutLocalSerialPort_exitsToHostTail() {
        // 无本地串口主机形态：tcp 档下 host/port 提交即过（UART 字段全缺也不被执法）
        // ——TCP 设备的核心价值场景
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "tcp");

        Map<String, Object> tcpInput = new LinkedHashMap<>();
        tcpInput.put("host", "192.168.1.50");
        tcpInput.put("port", "4001");
        ConfigFlowResult exit = host.handleStep("comm_config", tcpInput);
        assertEquals("TCP 档无 UART 字段应过校验（errors=" + exit.getErrors() + "）",
                "final_confirm", exit.getStepId());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transport", "tcp");
        expected.putAll(tcpInput);
        assertEquals(expected, host.getContext().getEntryData().get("comm_settings"));
    }

    @Test
    public void tcpSubmission_missingHost_redisplaysWithErrors() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "tcp");

        Map<String, Object> tcpInput = new LinkedHashMap<>();
        tcpInput.put("port", "4001");
        ConfigFlowResult r = host.handleStep("comm_config", tcpInput);
        assertEquals("校验失败应回显 comm_config", "comm_config", r.getStepId());
        assertNotNull("TCP 档缺 host 应在表单期报错（tcp schema 原生 required）", r.getErrors().get("host"));
        assertFalse(host.getContext().getEntryData().containsKey("comm_settings"));
    }

    // ==================== transport_select 预填（存量 entry 重配形态） ====================

    @Test
    public void transportSelectPrefill_fromStoredEntrySettings() {
        // reconfigure 存量 entry：core 启动时预载 entryData → transport_select 预填现有
        // transport（tcp entry 重配预填 tcp，用户直接下一步即回既有档位）
        HostFlow host = enterSubFlow(new SerialCommFlow());
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("transport", "tcp");
        stored.put("host", "192.168.1.50");
        stored.put("port", "4001");
        host.getContext().getEntryData().put("comm_settings", stored);

        ConfigFlowResult form = host.handleStep("transport_select", null);
        assertEquals("存量 entry 的 transport 预填", "tcp",
                field(form.getSchema(), "transport").getDefaultValue());
    }

    // ==================== 定制形态 vs 无参形态（builder 定制预填 vs 库标准预填） ====================

    @Test
    public void noArgMount_prefillsLibraryStandard() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "serial");
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("无参挂载：timeout 预填 = serial 库标准默认",
                Const.READ_TIMEOUT_MS.doubleValue(),
                ((Number) field(form.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    @Test
    public void builderMount_prefillsCustomizedDefaults_andCarriesIntoEntry() {
        SerialCommFlow customized = SerialCommFlow.builder()
                .schema(SerialCommConfigSchema.builder()
                        .baudrate(BaudRate.BAUD_19200)
                        .timeout(2000)
                        .build())
                .build();

        HostFlow host = enterSubFlow(customized);
        selectTransport(host, "serial");
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("builder 定制：timeout 预填 = 宿主定制值", 2000.0,
                ((Number) field(form.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
        assertEquals("builder 定制：波特率预填 = 宿主定制值", "19200",
                field(form.getSchema(), "baudrate").getDefaultValue());

        // 提交后落盘带定制值（写端预填随提交进 entry）
        Map<String, Object> commInput = commInput();
        commInput.put("baudrate", "19200");
        commInput.put("timeout", 2000);
        ConfigFlowResult exit = host.handleStep("comm_config", commInput);
        assertEquals("final_confirm", exit.getStepId());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transport", "serial");
        expected.putAll(commInput);
        assertEquals(expected, host.getContext().getEntryData().get("comm_settings"));
    }

    // ==================== defaults / defaultsTcp 动态预填（两级默认值 API） ====================

    @Test
    public void defaultsMount_hookReceivesEntryData_andPrefillsSerialProfile() {
        // 契约①：hook 收到宿主 entryData 且返回值用于预填；契约②（优先级）：与静态 schema
        // 并设时 defaults 优先（动态预填覆盖静态定制——Builder.defaults javadoc 同文）
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        SerialCommFlow flow = SerialCommFlow.builder()
                .schema(SerialCommConfigSchema.builder().timeout(9999).build())
                .defaults(entryData -> {
                    captured.set(entryData);
                    return SerialCommConfigSchema.builder().timeout(2000).build();
                })
                .build();

        HostFlow host = enterSubFlow(flow);   // device_config 提交 name=dev 即已入子 flow
        selectTransport(host, "serial");      // comm 步在档位确定后求值 hook
        assertEquals("hook 收到宿主 entryData（含 device_config 落的数据）",
                "dev", captured.get().get("name"));

        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("defaults 优先：timeout 预填 = hook 值（非静态 schema 的 9999）", 2000.0,
                ((Number) field(form.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    @Test
    public void defaultsTcpMount_hookPrefillsTcpProfile() {
        // tcp 档 hook：defaultsTcp 产物定制 tcp 表单预填（port 定制值可见）
        SerialCommFlow flow = SerialCommFlow.builder()
                .defaultsTcp(entryData -> TcpClientCommConfigSchema.builder().port(4196).build())
                .build();
        HostFlow host = enterSubFlow(flow);
        selectTransport(host, "tcp");
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("defaultsTcp：port 预填 = hook 定制值", 4196.0,
                ((Number) field(form.getSchema(), "port").getDefaultValue()).doubleValue(), 0.001);
        // serial 档不受 defaultsTcp 影响（hook 按档派发）
        SerialCommFlow noSerialHook = SerialCommFlow.builder()
                .defaultsTcp(entryData -> TcpClientCommConfigSchema.builder().port(4196).build())
                .build();
        HostFlow host2 = enterSubFlow(noSerialHook);
        selectTransport(host2, "serial");
        ConfigFlowResult serialForm = host2.handleStep("comm_config", null);
        assertEquals("defaultsTcp 不得影响 serial 档预填", Const.READ_TIMEOUT_MS.doubleValue(),
                ((Number) field(serialForm.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    @Test
    public void defaultsException_propagatesToFlowLayer_notSwallowed() {
        // 契约③：hook 抛异常炸到 flow 层（不吞、不转 errors map——严格模式不兜底）。
        // transport_select 提交链内即进 comm 首屏渲染（handleStep 习语），hook 求值异常
        // 沿链上抛，调用方拿到的就是原始 IllegalStateException
        SerialCommFlow flow = SerialCommFlow.builder()
                .defaults(entryData -> { throw new IllegalStateException("hook-boom"); })
                .build();
        HostFlow host = new HostFlow(flow);
        host.executeUserStep(null);
        host.handleStep("user", input("k", "v"));
        host.handleStep("device_config", input("name", "dev"));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("transport", "serial");
        try {
            host.handleStep("transport_select", choice);
            fail("defaults hook 抛异常必须炸到 flow 层");
        } catch (IllegalStateException expected) {
            assertEquals("hook-boom", expected.getMessage());
        }
    }

    @Test
    public void userFilledInputs_retainedInStepInputs_asEchoSource_overDefaults() {
        // 契约④（用户已填值优先于默认）的后端半面：core 在 handler 调用前把提交存
        // step_inputs[stepId]（校验失败也不丢）；前端以 step_inputs 盖 schema 预填。
        // 后端承诺：留存完整 + 重显表单仍带默认预填（未填字段保持默认，已填字段由前端盖）
        HostFlow host = enterSubFlow(new SerialCommFlow());
        selectTransport(host, "serial");
        Map<String, Object> bad = commInput();
        bad.put("baudrate", "99999");
        ConfigFlowResult r = host.handleStep("comm_config", bad);
        assertEquals("comm_config", r.getStepId());
        assertEquals("已填值留存 step_inputs（回显数据源不丢）", bad,
                host.getContext().getStepInputs().get("comm_config"));
        assertEquals("通道选择留存 step_inputs", "serial",
                ((Map<?, ?>) host.getContext().getStepInputs().get("transport_select")).get("transport"));
        ConfigFlowResult reshown = host.handleStep("comm_config", null);
        assertEquals("重显表单未填字段保持默认预填", Const.READ_TIMEOUT_MS.doubleValue(),
                ((Number) field(reshown.getSchema(), "timeout").getDefaultValue()).doubleValue(), 0.001);
    }

    // ==================== 辅助 ====================

    private static Map<String, Object> commInput() {
        // 枚举字段按真实表单行为提交字符串值
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("serial_port", "/dev/ttyUSB0");
        comm.put("baudrate", "9600");
        comm.put("data_bits", "8");
        comm.put("stop_bits", "1");
        comm.put("parity", "None");
        comm.put("flow_control", "0");
        comm.put("timeout", 500);
        return comm;
    }

    private static AbstractConfigItem<?> field(ConfigSchema s, String key) {
        return s.getFields().stream().filter(f -> f.getKey().equals(key)).findFirst().orElse(null);
    }

    private static Map<String, Object> input(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
