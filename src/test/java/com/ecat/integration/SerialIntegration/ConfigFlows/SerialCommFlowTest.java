package com.ecat.integration.SerialIntegration.ConfigFlows;

import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.integration.SerialIntegration.ConfigSchemas.BaudRate;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;
import com.ecat.integration.SerialIntegration.Const;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SerialCommFlow（串口通讯子 flow，单步形态）单元测试：
 * 挂载走读（入口即唯一步 → 出口交回宿主尾步）、落盘形状（扁平 7 字段整块）、
 * 校验失败回显、定制形态对照（宿主经 builder 注入定制预填 vs 无参挂载用库标准预填）。
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

    /** 驱动宿主直到子 flow 首屏（入口步 comm_config）显示。 */
    private static HostFlow enterSubFlow(SerialCommFlow commFlow) {
        HostFlow host = new HostFlow(commFlow);
        host.executeUserStep(null);
        host.handleStep("user", input("k", "v"));
        ConfigFlowResult r = host.handleStep("device_config", input("name", "dev"));
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, r.getType());
        assertEquals("宿主 device_config 提交后应进子 flow 入口屏", "comm_config", r.getStepId());
        return host;
    }

    // ==================== 挂载走读 ====================

    @Test
    public void submitCommConfig_exitsToHostTail_andPersistsFlatShape() {
        HostFlow host = enterSubFlow(new SerialCommFlow());

        Map<String, Object> commInput = commInput();
        ConfigFlowResult exit = host.handleStep("comm_config", commInput);
        assertEquals("子 flow 出口应交回宿主尾步（errors=" + exit.getErrors() + "）",
                ConfigFlowResult.ResultType.SHOW_FORM, exit.getType());
        assertEquals("提交合法通讯配置应落宿主尾步（errors=" + exit.getErrors() + "）",
                "final_confirm", exit.getStepId());
        assertEquals("final_confirm", host.getCurrentStep());

        // 落盘形状：comm_settings 整块（扁平 7 字段，与 11 仓现行主流一致，存量 entry 零迁移）
        assertEquals(commInput, host.getContext().getEntryData().get("comm_settings"));
        // 漫游面：子 flow 步数据进宿主 stepInputs（stepId 不变，reconfigure 漫游兼容）
        assertTrue(host.getContext().getStepInputs().containsKey("comm_config"));
    }

    @Test
    public void invalidInput_redisplaysCommConfigWithErrors() {
        HostFlow host = enterSubFlow(new SerialCommFlow());

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("serial_port", "/dev/ttyUSB0");
        bad.put("baudrate", "99999");   // 非枚举成员
        bad.put("data_bits", "8");
        bad.put("stop_bits", "1");
        bad.put("parity", "None");
        ConfigFlowResult r = host.handleStep("comm_config", bad);
        assertEquals("校验失败应回显子 flow 唯一步", "comm_config", r.getStepId());
        assertNotNull("应携带校验错误", r.getErrors().get("baudrate"));
        // 未过校验不得落盘、不得出口
        assertFalse(host.getContext().getEntryData().containsKey("comm_settings"));
    }

    @Test
    public void firstScreen_showsSerialFields() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
        ConfigFlowResult form = host.handleStep("comm_config", null);
        assertEquals("comm_config", form.getStepId());
        assertNotNull("表单应含 serial_port 字段", field(form.getSchema(), "serial_port"));
        assertNotNull("表单应含 flow_control 字段", field(form.getSchema(), "flow_control"));
        assertNotNull("表单应含 timeout 字段", field(form.getSchema(), "timeout"));
    }

    // ==================== 定制形态 vs 无参形态（builder 定制预填 vs 库标准预填） ====================

    @Test
    public void noArgMount_prefillsLibraryStandard() {
        HostFlow host = enterSubFlow(new SerialCommFlow());
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
        assertEquals(commInput, host.getContext().getEntryData().get("comm_settings"));
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
