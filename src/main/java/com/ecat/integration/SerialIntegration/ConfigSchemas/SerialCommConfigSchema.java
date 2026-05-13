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
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;
import com.ecat.core.ConfigFlow.ConfigItem.DynamicEnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.NumericConfigItem;
import com.ecat.integration.SerialIntegration.Const;
import com.fazecast.jSerialComm.SerialPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 串口通讯 Schema - 可被其他集成复用
 * <p>
 * 定义了串口通讯的通用配置字段：
 * <ul>
 *   <li>serial_port - 串口设备路径</li>
 *   <li>baudrate - 波特率</li>
 *   <li>data_bits - 数据位</li>
 *   <li>stop_bits - 停止位</li>
 *   <li>parity - 校验位</li>
 *   <li>flow_control - 流控</li>
 *   <li>timeout - 超时时间</li>
 * </ul>
 * <p>
 * 使用方式：
 * <ul>
 *   <li>无参构造 → 标准默认值（向后兼容）</li>
 *   <li>Builder 构造 → 自定义默认值</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialCommConfigSchema implements ConfigSchemaProvider {

    // ========== 实例级默认值 ==========

    private final BaudRate defaultBaudRate;
    private final DataBits defaultDataBits;
    private final StopBits defaultStopBits;
    private final Parity defaultParity;
    private final FlowControl defaultFlowControl;
    private final int defaultTimeout;

    // ========== 测试用端口注入 ==========

    /**
     * 测试用端口注入点。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     * 设置后 {@link #getAvailablePorts()} 将直接返回此 Supplier 的结果，跳过 jSerialComm 硬件调用。
     * <p>
     * 用法示例（在测试的 @Before 中设置，@After 中清除）：
     * <pre>
     *   SerialCommConfigSchema.setTestPortSupplier(
     *       () -&gt; SerialCommConfigSchema.createTestPorts("ttyUSB0"));
     * </pre>
     */
    private static Supplier<Map<String, String>> testPortSupplier = null;

    /**
     * 注入测试用虚拟串口列表。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     *
     * @param supplier 返回虚拟端口 Map 的 Supplier，传 null 等同于 {@link #clearTestPortSupplier()}
     */
    public static void setTestPortSupplier(Supplier<Map<String, String>> supplier) {
        testPortSupplier = supplier;
    }

    /**
     * 清除测试用端口注入。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     */
    public static void clearTestPortSupplier() {
        testPortSupplier = null;
    }

    /**
     * 创建一个标准的测试用虚拟串口列表。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     *
     * @param portNames 虚拟端口名称，如 "ttyUSB0", "ttyUSB1"
     * @return 格式为 {"" -&gt; "-- 请选择串口 --", "ttyUSB0" -&gt; "ttyUSB0", ...} 的 Map
     */
    public static Map<String, String> createTestPorts(String... portNames) {
        Map<String, String> ports = new LinkedHashMap<>();
        ports.put("", "-- 请选择串口 --");
        for (String name : portNames) {
            ports.put(name, name);
        }
        return ports;
    }

    // ========== 构造函数 ==========

    /**
     * 无参构造 → 标准默认值（向后兼容）
     */
    public SerialCommConfigSchema() {
        this.defaultBaudRate = BaudRate.BAUD_9600;
        this.defaultDataBits = DataBits.EIGHT;
        this.defaultStopBits = StopBits.ONE;
        this.defaultParity = Parity.NONE;
        this.defaultFlowControl = FlowControl.NONE;
        this.defaultTimeout = Const.READ_TIMEOUT_MS;
    }

    /**
     * Builder 构造 → 自定义默认值
     */
    private SerialCommConfigSchema(Builder builder) {
        this.defaultBaudRate = builder.baudRate;
        this.defaultDataBits = builder.dataBits;
        this.defaultStopBits = builder.stopBits;
        this.defaultParity = builder.parity;
        this.defaultFlowControl = builder.flowControl;
        this.defaultTimeout = builder.timeout;
    }

    // ========== ConfigSchemaProvider ==========

    @Override
    public String getI18nKeyPrefix() {
        return "config_schemas.serial_comm";
    }

    @Override
    public ConfigSchema createSchema() {
        ConfigSchema schema = new ConfigSchema()
            // 串口选择
            .addField(new DynamicEnumConfigItem("serial_port", true, "/dev/ttyUSB0",
                    SerialCommConfigSchema::getAvailablePorts)
                .displayName("串口"))
            // 波特率
            .addField(new EnumConfigItem("baudrate", true, defaultBaudRate.getValue())
                .displayName("波特率")
                .addOptions(BaudRate.toMap())
                .buildValidator())
            // 数据位
            .addField(new EnumConfigItem("data_bits", true, defaultDataBits.getValue())
                .displayName("数据位")
                .addOptions(DataBits.toMap())
                .buildValidator())
            // 停止位
            .addField(new EnumConfigItem("stop_bits", true, defaultStopBits.getValue())
                .displayName("停止位")
                .addOptions(StopBits.toMap())
                .buildValidator())
            // 校验位
            .addField(new EnumConfigItem("parity", true, defaultParity.getValue())
                .displayName("校验位")
                .addOptions(Parity.toMap())
                .buildValidator())
            // 流控 (value = SerialPort.FLOW_CONTROL_* 常量组合)
            .addField(new EnumConfigItem("flow_control", false, defaultFlowControl.getValue())
                .displayName("流控")
                .addOptions(FlowControl.toMap())
                .buildValidator())
            // 超时时间
            .addField(new NumericConfigItem("timeout", false, defaultTimeout)
                .displayName("超时时间(ms)")
                .range(100.0, 60000.0));

        schema.initI18n(this);
        return schema;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BaudRate baudRate = BaudRate.BAUD_9600;
        private DataBits dataBits = DataBits.EIGHT;
        private StopBits stopBits = StopBits.ONE;
        private Parity parity = Parity.NONE;
        private FlowControl flowControl = FlowControl.NONE;
        private int timeout = Const.READ_TIMEOUT_MS;

        public Builder baudrate(BaudRate baudRate) { this.baudRate = baudRate; return this; }
        public Builder dataBits(DataBits dataBits) { this.dataBits = dataBits; return this; }
        public Builder stopBits(StopBits stopBits) { this.stopBits = stopBits; return this; }
        public Builder parity(Parity parity) { this.parity = parity; return this; }
        public Builder flowControl(FlowControl flowControl) { this.flowControl = flowControl; return this; }
        public Builder timeout(int timeout) { this.timeout = timeout; return this; }

        public SerialCommConfigSchema build() {
            return new SerialCommConfigSchema(this);
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 获取系统可用的串口列表
     * <p>
     * 返回格式：
     * <ul>
     *   <li>有串口时：首项为"请选择串口"提示，后续为实际串口列表</li>
     *   <li>无串口时：仅返回"无串口信息"提示项</li>
     * </ul>
     *
     * @return 串口选项映射 (portName -> displayName)
     */
    public static Map<String, String> getAvailablePorts() {
        // 测试注入点：如果设置了测试端口 Supplier，直接返回虚拟端口，不调用 jSerialComm
        if (testPortSupplier != null) {
            return testPortSupplier.get();
        }

        Map<String, String> ports = new LinkedHashMap<>();
        SerialPort[] serialPorts = SerialPort.getCommPorts();

        if (serialPorts != null && serialPorts.length > 0) {
            // 首项：请选择串口（默认选项，不预设具体串口）
            ports.put("", "-- 请选择串口 --");

            // 添加实际串口列表
            for (SerialPort port : serialPorts) {
                String portName = port.getSystemPortName();
                String description = port.getPortDescription();
                // 显示格式: "描述 (端口名)" 或直接端口名
                String displayName = (description != null && !description.trim().isEmpty())
                        ? description + " (" + portName + ")"
                        : portName;
                ports.put(portName, displayName);
            }
        } else {
            // 无串口时提示 - 使用特殊占位值，空字符串不会提交到后端
            ports.put("", "-- 无串口信息 --");
        }

        return ports;
    }
}
