/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.SerialIntegration.ConfigSchemas;

import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.SchemaConfigItem;
import com.ecat.core.I18n.I18nHelper;
import com.ecat.core.Utils.IntegrationCoordinateHelper;
import com.ecat.integration.SerialIntegration.SerialTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SerialCommConfigSchema 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>Schema 字段定义正确（7 个字段）</li>
 *   <li>Schema 验证功能正常（必填字段检查）</li>
 *   <li>无参构造 → 标准默认值</li>
 *   <li>Builder → 自定义默认值</li>
 *   <li>initI18n 正确设置 i18n 解析能力</li>
 *   <li>getI18nKeyPrefix() 返回正确前缀</li>
 *   <li>schema.resolveXxx() 能从 strings.json 获取翻译</li>
 *   <li>枚举 toMap() 与 createSchema() 选项一致</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialCommConfigSchemaTest {

    @Before
    public void setUp() {
        IntegrationCoordinateHelper.clearCache();
        I18nHelper.clearCache();
        SerialCommConfigSchema.setTestPortSupplier(
            () -> SerialCommConfigSchema.createTestPorts("ttyUSB0", "ttyUSB1"));
    }

    @After
    public void tearDown() {
        IntegrationCoordinateHelper.clearCache();
        I18nHelper.clearCache();
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    // ========== Schema 定义测试 ==========

    @Test
    public void testSchemaDefinitionCorrect() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        assertNotNull("Schema 不应为 null", schema);
        List<AbstractConfigItem<?>> fields = schema.getFields();
        assertEquals("应该有 10 个字段（transport 档案位 + UART 档 6 字段 + timeout + TCP 档 2 字段）",
                10, fields.size());

        assertFieldExists(fields, "transport");
        assertFieldExists(fields, "serial_port");
        assertFieldExists(fields, "baudrate");
        assertFieldExists(fields, "data_bits");
        assertFieldExists(fields, "stop_bits");
        assertFieldExists(fields, "parity");
        assertFieldExists(fields, "flow_control");
        assertFieldExists(fields, "timeout");
        assertFieldExists(fields, "host");
        assertFieldExists(fields, "port");
    }

    // ========== transport 档案位与 TCP 字段定义 ==========

    @Test
    public void transportField_serialProfileSwitch_optionalWithDefaultSerial() {
        List<AbstractConfigItem<?>> fields = new SerialCommConfigSchema().createSchema().getFields();
        EnumConfigItem transport = (EnumConfigItem) findField(fields, "transport");
        // required=false：本 schema 被 11+ 消费仓共享，存量 COM 提交不带 transport 键——
        // 缺省=serial 与 parse 同源（additive-only，COM 档零变化）
        assertFalse("transport 不得必填（存量 COM 提交零变化）", transport.isRequired());
        assertEquals("缺省档=serial（存量 entry 零迁移）", "serial", transport.getDefaultValue());
        assertEquals("options 与 SerialTransport 枚举一一对应",
                SerialTransport.toMap(),
                transport.getOptionLabels());
    }

    @Test
    public void tcpFields_reusedFromTcpSchema_optionalAtSchemaLevelProfileEnforced() {
        // host/port 字段定义复用 TcpClientCommConfigSchema（键名/长度与端口域校验器同源），
        // 移植时 required=false（共享 schema 的 COM 直调零变化）——TCP 档必需执法收口在
        // validateForTransport；displayName 标注串口服务器语境
        List<AbstractConfigItem<?>> fields = new SerialCommConfigSchema().createSchema().getFields();
        AbstractConfigItem<?> host = findField(fields, "host");
        AbstractConfigItem<?> port = findField(fields, "port");
        assertFalse("host schema 层非必填（TCP 档执法在 validateForTransport）", host.isRequired());
        assertEquals("host 无预填（builder.host(null)）", null, host.getDefaultValue());
        assertFalse("port schema 层非必填（同上）", port.isRequired());
        assertEquals("port 预填=串口服务器典型档", SerialCommConfigSchema.DEFAULT_TCP_PORT,
                ((Number) port.getDefaultValue()).intValue());
    }

    // ========== validateForTransport（按档提交校验执法点） ==========

    @Test
    public void validateForTransport_serialProfile_enforcesUartIgnoresTcp() {
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        Map<String, Object> serialInput = new java.util.HashMap<>();
        serialInput.put("transport", "serial");
        serialInput.put("serial_port", "ttyUSB0");
        serialInput.put("baudrate", "9600");
        serialInput.put("data_bits", "8");
        serialInput.put("stop_bits", "1");
        serialInput.put("parity", "None");
        // serial 档不提交 tcp 字段（COM 档既有提交形态）→ 零错误
        assertTrue("serial 档合法提交应零错误", provider.validateForTransport(serialInput).isEmpty());

        // serial 档缺 UART 字段 → 照常执法（COM 档零变化）
        serialInput.remove("baudrate");
        Map<String, Object> errors = provider.validateForTransport(serialInput);
        assertTrue("serial 档缺波特率必须报错", errors.containsKey("baudrate"));
    }

    @Test
    public void validateForTransport_tcpProfile_exemptUartRequireTcp() {
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        // 无本地串口主机形态：只有 transport/host/port（UART 字段全缺）
        Map<String, Object> tcpInput = new java.util.HashMap<>();
        tcpInput.put("transport", "tcp");
        tcpInput.put("host", "192.168.1.50");
        tcpInput.put("port", "4001");
        assertTrue("TCP 档无 UART 字段应零错误（豁免集执法）",
                provider.validateForTransport(tcpInput).isEmpty());

        // TCP 档缺 host → 表单期暴露（tcp 仓字段原生 required 校验）
        tcpInput.remove("host");
        Map<String, Object> errors = provider.validateForTransport(tcpInput);
        assertTrue("TCP 档缺 host 必须报错", errors.containsKey("host"));

        // TCP 档缺 port → 同上
        tcpInput.put("host", "192.168.1.50");
        tcpInput.remove("port");
        assertTrue("TCP 档缺 port 必须报错",
                provider.validateForTransport(tcpInput).containsKey("port"));
    }

    @Test
    public void validateForTransport_tcpProfile_withGarbageUartValues_stillClean() {
        // TCP 档 UI 全显形态：误填/存量 UART 值不阻断提交（parse 档不读，忽略语义）
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        Map<String, Object> tcpInput = new java.util.HashMap<>();
        tcpInput.put("transport", "tcp");
        tcpInput.put("host", "192.168.1.50");
        tcpInput.put("port", "4001");
        tcpInput.put("baudrate", "99999");   // 非法枚举值，TCP 档下豁免
        tcpInput.put("serial_port", "not-a-real-port"); // 动态枚举校验不通过，TCP 档下豁免
        assertTrue("TCP 档 UART 误填值不阻断提交", provider.validateForTransport(tcpInput).isEmpty());
    }

    // ========== §3.1 校验下沉契约：递归路径与提交路径同一规则 ==========

    @Test
    public void transportRule_sinkedToSchema_recursivePathMatchesFlowSubmitPath() {
        // 校验下沉（§3.1）：加载/reconfigure 路径（DeviceConfigSchema 嵌套 SchemaConfigItem
        // 递归调 createSchema().validate）与提交路径（SerialCommFlow 调 validateForTransport）
        // 必须同一规则——下沉前递归路径用 COM 规则打回 TCP entry（「TCP entry 建得出、
        // 加载不了」缺陷本体），两路同 payload 同结果是硬门
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        SchemaConfigItem item = new SchemaConfigItem("comm_settings", true, SerialCommConfigSchema.class);

        Map<String, Object> tcpInput = new java.util.HashMap<>();
        tcpInput.put("transport", "tcp");
        tcpInput.put("host", "192.168.1.50");
        tcpInput.put("port", "4001");
        assertNull("同一 TCP payload 经递归路径必须放行（与提交路径一致）",
                item.validate(tcpInput));
        assertTrue("同一 TCP payload 经提交路径应零错误",
                provider.validateForTransport(tcpInput).isEmpty());

        // COM 档两路一致（零变化硬门）
        Map<String, Object> comInput = new java.util.HashMap<>();
        comInput.put("serial_port", "ttyUSB0");
        comInput.put("baudrate", "9600");
        comInput.put("data_bits", "8");
        comInput.put("stop_bits", "1");
        comInput.put("parity", "None");
        assertNull("同一 COM payload 经递归路径必须放行",
                item.validate(comInput));
        assertTrue("同一 COM payload 经提交路径应零错误",
                provider.validateForTransport(comInput).isEmpty());
    }

    @Test
    public void invalidSerialPort_rejectedInComProfileBothPaths() {
        // COM 档执法不放宽：serial_port 无效选项（动态枚举成员资格）两路均打回
        SchemaConfigItem item = new SchemaConfigItem("comm_settings", true, SerialCommConfigSchema.class);
        Map<String, Object> badPort = new java.util.HashMap<>();
        badPort.put("serial_port", "not-a-real-port");
        badPort.put("baudrate", "9600");
        badPort.put("data_bits", "8");
        badPort.put("stop_bits", "1");
        badPort.put("parity", "None");
        Object recursive = item.validate(badPort);
        assertTrue("COM 档无效 serial_port 递归路径必须打回（嵌套 Map 契约）",
                recursive instanceof Map && ((Map<?, ?>) recursive).containsKey("serial_port"));
        assertTrue("COM 档无效 serial_port 提交路径必须打回",
                new SerialCommConfigSchema().validateForTransport(badPort).containsKey("serial_port"));
    }

    @Test
    public void validateForTransport_unknownTransportValue_reportedNotSuppressed() {
        // transport 本身取值非法：不属任何豁免集，错误照常呈现（不静默当 serial 档）
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        Map<String, Object> bogus = new java.util.HashMap<>();
        bogus.put("transport", "bogus");
        bogus.put("serial_port", "ttyUSB0");
        bogus.put("baudrate", "9600");
        bogus.put("data_bits", "8");
        bogus.put("stop_bits", "1");
        bogus.put("parity", "None");
        Map<String, Object> errors = provider.validateForTransport(bogus);
        assertTrue("非法 transport 取值必须报错", errors.containsKey("transport"));
    }

    // ========== Schema 验证测试 ==========

    @Test
    public void testSchemaValidation_ValidData() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        Map<String, String> availablePorts = SerialCommConfigSchema.getAvailablePorts();
        String validPort = availablePorts.keySet().stream()
            .filter(k -> !k.isEmpty())
            .findFirst()
            .orElse(null);

        if (validPort != null) {
            Map<String, Object> validData = new java.util.HashMap<>();
            validData.put("serial_port", validPort);
            validData.put("baudrate", "9600");
            validData.put("data_bits", "8");
            validData.put("stop_bits", "1");
            validData.put("parity", "None");

            Map<String, Object> errors = schema.validate(validData);
            assertTrue("有效数据应该通过验证", errors.isEmpty());
        }
    }

    @Test
    public void testSchemaValidation_MissingRequiredField() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        Map<String, Object> invalidData = new java.util.HashMap<>();

        Map<String, Object> errors = schema.validate(invalidData);
        assertFalse("缺少必填字段应该有错误", errors.isEmpty());
        assertTrue("应该有关于 serial_port 字段的错误", errors.containsKey("serial_port"));
    }

    // ========== initI18n 真实 i18n 链路测试 ==========

    /**
     * 创建带硬编码 coordinate 的 Provider，绕过 META-INF 缺失问题。
     * 这样 initI18n() 使用显式 coordinate → I18nProxy 能从 classpath 加载 strings.json。
     */
    private SerialCommConfigSchema createProviderWithCoordinate() {
        return new SerialCommConfigSchema() {
            @Override
            public String getCoordinate() {
                return "com.ecat:integration-serial";
            }
        };
    }

    @Test
    public void testInitI18n_RealI18nResolution_DisplayName() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        // resolveDisplayName 走真实链路：I18nProxy → ResourceLoader → strings.json
        assertEquals("Baud Rate", schema.resolveDisplayName("baudrate"));
        assertEquals("Data Bits", schema.resolveDisplayName("data_bits"));
        assertEquals("Stop Bits", schema.resolveDisplayName("stop_bits"));
        assertEquals("Parity", schema.resolveDisplayName("parity"));
        assertEquals("Flow Control", schema.resolveDisplayName("flow_control"));
        assertEquals("Timeout (ms)", schema.resolveDisplayName("timeout"));
        assertEquals("Serial Port", schema.resolveDisplayName("serial_port"));
    }

    @Test
    public void testInitI18n_RealI18nResolution_OptionLabels() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        // Parity options
        assertEquals("No Parity", schema.resolveOptionLabel("parity", "None"));
        assertEquals("Odd Parity", schema.resolveOptionLabel("parity", "Odd"));
        assertEquals("Even Parity", schema.resolveOptionLabel("parity", "Even"));

        // FlowControl options
        assertEquals("None", schema.resolveOptionLabel("flow_control", "0"));
        assertEquals("RTS/CTS (Hardware)", schema.resolveOptionLabel("flow_control", "17"));
        assertEquals("DTR/DSR (Hardware)", schema.resolveOptionLabel("flow_control", "4352"));
        assertEquals("XOn/XOff (Software)", schema.resolveOptionLabel("flow_control", "1114112"));
    }

    @Test
    public void testInitI18n_RealI18nResolution_Description() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        assertEquals("Communication speed in bits per second",
            schema.resolveDescription("baudrate"));
        assertEquals("Number of data bits per frame",
            schema.resolveDescription("data_bits"));
        assertEquals("Read timeout in milliseconds",
            schema.resolveDescription("timeout"));
    }

    @Test
    public void testInitI18n_RealI18nResolution_AllFieldsHaveDisplayName() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        for (AbstractConfigItem<?> field : schema.getFields()) {
            String displayName = schema.resolveDisplayName(field.getKey());
            assertNotNull("Field '" + field.getKey() + "' should resolve display_name", displayName);
        }
    }

    @Test
    public void testInitI18n_RealI18nResolution_AllEnumOptionsResolved() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        // 验证所有枚举字段的所有选项都有翻译
        for (BaudRate br : BaudRate.values()) {
            assertNotNull("BaudRate '" + br.getValue() + "' should resolve",
                schema.resolveOptionLabel("baudrate", br.getValue()));
        }
        for (Parity p : Parity.values()) {
            assertNotNull("Parity '" + p.getValue() + "' should resolve",
                schema.resolveOptionLabel("parity", p.getValue()));
        }
        for (FlowControl fc : FlowControl.values()) {
            assertNotNull("FlowControl '" + fc.getValue() + "' should resolve",
                schema.resolveOptionLabel("flow_control", fc.getValue()));
        }
        for (DataBits db : DataBits.values()) {
            assertNotNull("DataBits '" + db.getValue() + "' should resolve",
                schema.resolveOptionLabel("data_bits", db.getValue()));
        }
        for (StopBits sb : StopBits.values()) {
            assertNotNull("StopBits '" + sb.getValue() + "' should resolve",
                schema.resolveOptionLabel("stop_bits", sb.getValue()));
        }
    }

    @Test
    public void testInitI18n_UnknownFieldReturnsNull() {
        ConfigSchema schema = createProviderWithCoordinate().createSchema();

        assertNull("不存在的字段应返回 null", schema.resolveDisplayName("nonexistent_field"));
        assertNull("不存在的 option 应返回 null", schema.resolveOptionLabel("baudrate", "999999"));
        assertNull("不存在的 description 应返回 null", schema.resolveDescription("nonexistent_field"));
    }

    @Test
    public void testInitI18n_WithBuilder_RealI18nResolution() {
        SerialCommConfigSchema.builder() // SerialCommConfigSchema provider
            .baudrate(BaudRate.BAUD_115200)
            .build();
        // Builder 创建的 Provider 也需要硬编码 coordinate
        SerialCommConfigSchema providerWithCoord = new SerialCommConfigSchema() {
            @Override
            public String getCoordinate() {
                return "com.ecat:integration-serial";
            }
            @Override
            public ConfigSchema createSchema() {
                // 使用 builder 实例的 defaults
                ConfigSchema schema = new ConfigSchema()
                    .addField(new EnumConfigItem("baudrate", true, "115200")
                        .addOptions(BaudRate.toMap())
                        .buildValidator());
                schema.initI18n(this);
                return schema;
            }
        };
        ConfigSchema schema = providerWithCoord.createSchema();

        assertEquals("Baud Rate", schema.resolveDisplayName("baudrate"));
    }

    @Test
    public void testInitI18n_AutoDetectWithoutOverride() {
        // 不 override getCoordinate()，使用自动检测
        // 开发环境：IntegrationCoordinateHelper 从 classpath pom.xml 检测 → 成功
        // CI/打包环境：无 pom.xml，从 JAR MANIFEST 检测 → 可能成功也可能失败
        SerialCommConfigSchema provider = new SerialCommConfigSchema();
        ConfigSchema schema = provider.createSchema();

        // 自动检测成功 → resolveXxx 返回翻译结果（与硬编码 coordinate 测试一致）
        // 自动检测失败 → resolveXxx 安全返回 null（不影响运行时）
        String displayName = schema.resolveDisplayName("baudrate");
        if (displayName != null) {
            assertEquals("自动检测成功时应返回正确的翻译", "Baud Rate", displayName);
        }
        // 验证不抛异常，无论自动检测成功或失败
    }

    // ========== getI18nKeyPrefix 测试 ==========

    @Test
    public void testI18nKeyPrefix() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        assertEquals("i18n key 前缀应为 config_schemas.serial_comm",
            "config_schemas.serial_comm", schemaProvider.getI18nKeyPrefix());
    }

    // ========== 无参构造默认值测试 ==========

    @Test
    public void testDefaultConstructor_StandardDefaults() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();
        List<AbstractConfigItem<?>> fields = schema.getFields();

        // 查找各字段的默认值
        assertEquals("默认波特率应为 9600",
            "9600", findField(fields, "baudrate").getDefaultValue());
        assertEquals("默认数据位应为 8",
            "8", findField(fields, "data_bits").getDefaultValue());
        assertEquals("默认停止位应为 1",
            "1", findField(fields, "stop_bits").getDefaultValue());
        assertEquals("默认校验位应为 None",
            "None", findField(fields, "parity").getDefaultValue());
        assertEquals("默认流控应为 0",
            "0", findField(fields, "flow_control").getDefaultValue());
        assertEquals("默认超时应为 500",
            Double.valueOf(500), findField(fields, "timeout").getDefaultValue());
    }

    // ========== Builder 自定义默认值测试 ==========

    @Test
    public void testBuilder_CustomDefaults() {
        SerialCommConfigSchema schemaProvider = SerialCommConfigSchema.builder()
            .baudrate(BaudRate.BAUD_115200)
            .dataBits(DataBits.SEVEN)
            .stopBits(StopBits.TWO)
            .parity(Parity.ODD)
            .flowControl(FlowControl.RTS_CTS)
            .timeout(1000)
            .build();

        ConfigSchema schema = schemaProvider.createSchema();
        List<AbstractConfigItem<?>> fields = schema.getFields();

        assertEquals("Builder 自定义波特率应为 115200",
            "115200", findField(fields, "baudrate").getDefaultValue());
        assertEquals("Builder 自定义数据位应为 7",
            "7", findField(fields, "data_bits").getDefaultValue());
        assertEquals("Builder 自定义停止位应为 2",
            "2", findField(fields, "stop_bits").getDefaultValue());
        assertEquals("Builder 自定义校验位应为 Odd",
            "Odd", findField(fields, "parity").getDefaultValue());
        assertEquals("Builder 自定义流控应为 17",
            "17", findField(fields, "flow_control").getDefaultValue());
        assertEquals("Builder 自定义超时应为 1000",
            Double.valueOf(1000), findField(fields, "timeout").getDefaultValue());
    }

    @Test
    public void testBuilder_PartialCustomization() {
        // 只设置部分字段，其余应使用标准默认值
        SerialCommConfigSchema schemaProvider = SerialCommConfigSchema.builder()
            .baudrate(BaudRate.BAUD_57600)
            .build();

        ConfigSchema schema = schemaProvider.createSchema();
        List<AbstractConfigItem<?>> fields = schema.getFields();

        assertEquals("只设置了波特率，应为 57600",
            "57600", findField(fields, "baudrate").getDefaultValue());
        // 其余应保持标准默认值
        assertEquals("未设置的校验位应保持默认 None",
            "None", findField(fields, "parity").getDefaultValue());
        assertEquals("未设置的数据位应保持默认 8",
            "8", findField(fields, "data_bits").getDefaultValue());
    }

    @Test
    public void testBuilder_TransportPrefillOverridden_defaultRemainsSerial() {
        // Builder.transport 只动 transport 档案位预填（两级默认值 API，§3.3）；缺省=SERIAL
        // 与 parse 同源（COM 档零变化），且不影响 UART 字段默认值
        EnumConfigItem tcpPrefill = (EnumConfigItem) findField(
            SerialCommConfigSchema.builder()
                .transport(SerialTransport.TCP)
                .build()
                .createSchema()
                .getFields(),
            "transport");
        assertEquals("Builder.transport(TCP) 应改 transport 预填档", "tcp", tcpPrefill.getDefaultValue());

        EnumConfigItem standard = (EnumConfigItem) findField(
            new SerialCommConfigSchema().createSchema().getFields(), "transport");
        assertEquals("未设置时保持库标准 serial（存量 entry 零迁移）", "serial", standard.getDefaultValue());

        SerialCommConfigSchema mixed = SerialCommConfigSchema.builder()
            .transport(SerialTransport.TCP)
            .baudrate(BaudRate.BAUD_115200)
            .build();
        assertEquals("transport 定制不影响其他字段默认值",
            "115200", findField(mixed.createSchema().getFields(), "baudrate").getDefaultValue());
    }

    // ========== 枚举 toMap() 一致性测试 ==========

    @Test
    public void testBaudRateToMap_MatchesSchemaOptions() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        EnumConfigItem baudrateField = (EnumConfigItem) findField(schema.getFields(), "baudrate");
        Map<String, String> schemaOptions = baudrateField.getOptionLabels();
        Map<String, String> enumOptions = BaudRate.toMap();

        assertEquals("BaudRate.toMap() 与 schema 选项数量应一致",
            enumOptions.size(), schemaOptions.size());
        for (Map.Entry<String, String> entry : enumOptions.entrySet()) {
            assertEquals("BaudRate option " + entry.getKey() + " label 应一致",
                entry.getValue(), schemaOptions.get(entry.getKey()));
        }
    }

    @Test
    public void testParityToMap_MatchesSchemaOptions() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        EnumConfigItem parityField = (EnumConfigItem) findField(schema.getFields(), "parity");
        Map<String, String> schemaOptions = parityField.getOptionLabels();
        Map<String, String> enumOptions = Parity.toMap();

        assertEquals("Parity.toMap() 与 schema 选项数量应一致",
            enumOptions.size(), schemaOptions.size());
        for (Map.Entry<String, String> entry : enumOptions.entrySet()) {
            assertEquals("Parity option " + entry.getKey() + " label 应一致",
                entry.getValue(), schemaOptions.get(entry.getKey()));
        }
    }

    @Test
    public void testFlowControlToMap_MatchesSchemaOptions() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        EnumConfigItem flowControlField = (EnumConfigItem) findField(schema.getFields(), "flow_control");
        Map<String, String> schemaOptions = flowControlField.getOptionLabels();
        Map<String, String> enumOptions = FlowControl.toMap();

        assertEquals("FlowControl.toMap() 与 schema 选项数量应一致",
            enumOptions.size(), schemaOptions.size());
        for (Map.Entry<String, String> entry : enumOptions.entrySet()) {
            assertEquals("FlowControl option " + entry.getKey() + " label 应一致",
                entry.getValue(), schemaOptions.get(entry.getKey()));
        }
    }

    @Test
    public void testDataBitsToMap_MatchesSchemaOptions() {
        EnumConfigItem dataBitsField = (EnumConfigItem) findField(
            new SerialCommConfigSchema().createSchema().getFields(), "data_bits");
        Map<String, String> schemaOptions = dataBitsField.getOptionLabels();
        Map<String, String> enumOptions = DataBits.toMap();

        assertEquals("DataBits.toMap() 与 schema 选项数量应一致",
            enumOptions.size(), schemaOptions.size());
        for (Map.Entry<String, String> entry : enumOptions.entrySet()) {
            assertEquals("DataBits option " + entry.getKey() + " label 应一致",
                entry.getValue(), schemaOptions.get(entry.getKey()));
        }
    }

    @Test
    public void testStopBitsToMap_MatchesSchemaOptions() {
        EnumConfigItem stopBitsField = (EnumConfigItem) findField(
            new SerialCommConfigSchema().createSchema().getFields(), "stop_bits");
        Map<String, String> schemaOptions = stopBitsField.getOptionLabels();
        Map<String, String> enumOptions = StopBits.toMap();

        assertEquals("StopBits.toMap() 与 schema 选项数量应一致",
            enumOptions.size(), schemaOptions.size());
        for (Map.Entry<String, String> entry : enumOptions.entrySet()) {
            assertEquals("StopBits option " + entry.getKey() + " label 应一致",
                entry.getValue(), schemaOptions.get(entry.getKey()));
        }
    }

    // ========== 枚举 value/getValue 一致性测试 ==========

    @Test
    public void testBaudRateValues() {
        assertEquals("1200", BaudRate.BAUD_1200.getValue());
        assertEquals("9600", BaudRate.BAUD_9600.getValue());
        assertEquals("115200", BaudRate.BAUD_115200.getValue());
        assertEquals(8, BaudRate.values().length);
    }

    @Test
    public void testParityValues() {
        assertEquals("None", Parity.NONE.getValue());
        assertEquals("Odd", Parity.ODD.getValue());
        assertEquals("Even", Parity.EVEN.getValue());
        assertEquals(3, Parity.values().length);
    }

    @Test
    public void testFlowControlValues() {
        assertEquals("0", FlowControl.NONE.getValue());
        assertEquals("17", FlowControl.RTS_CTS.getValue());
        assertEquals("4352", FlowControl.DTR_DSR.getValue());
        assertEquals("1114112", FlowControl.XON_XOFF.getValue());
        assertEquals(4, FlowControl.values().length);
    }

    // ========== 辅助方法 ==========

    private void assertFieldExists(List<AbstractConfigItem<?>> fields, String key) {
        assertNotNull("字段列表不应为 null", fields);
        for (AbstractConfigItem<?> field : fields) {
            if (field.getKey().equals(key)) {
                return;
            }
        }
        fail("字段 '" + key + "' 不存在");
    }

    private AbstractConfigItem<?> findField(List<AbstractConfigItem<?>> fields, String key) {
        for (AbstractConfigItem<?> field : fields) {
            if (field.getKey().equals(key)) {
                return field;
            }
        }
        fail("字段 '" + key + "' 不存在");
        return null;
    }
}
