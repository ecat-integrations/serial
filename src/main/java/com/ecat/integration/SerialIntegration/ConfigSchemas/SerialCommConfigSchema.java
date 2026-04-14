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
 *   <li>timeout - 超时时间</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialCommConfigSchema implements ConfigSchemaProvider {

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
     * 在测试的 @Before 中调用此方法注入虚拟端口，
     * 使 {@link DynamicEnumConfigItem} 的 validate 能在无物理串口的机器上通过。
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
     * 必须在测试的 @After 中调用，避免影响其他测试用例。
     */
    public static void clearTestPortSupplier() {
        testPortSupplier = null;
    }

    /**
     * 创建一个标准的测试用虚拟串口列表。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     * 返回的 Map 格式与 {@link #getAvailablePorts()} 一致（首项为提示项，后续为端口列表），
     * 可直接传给 {@link #setTestPortSupplier(Supplier)}。
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

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
            // 串口选择
            .addField(new DynamicEnumConfigItem("serial_port", true, "/dev/ttyUSB0",
                    SerialCommConfigSchema::getAvailablePorts)
                .displayName("串口"))
            // 波特率
            .addField(new EnumConfigItem("baudrate", true, "9600")
                .displayName("波特率")
                .addOptions(createBaudRates())
                .buildValidator())
            // 数据位
            .addField(new EnumConfigItem("data_bits", true, "8")
                .displayName("数据位")
                .addOption("7", "7")
                .addOption("8", "8")
                .buildValidator())
            // 停止位
            .addField(new EnumConfigItem("stop_bits", true, "1")
                .displayName("停止位")
                .addOption("1", "1")
                .addOption("2", "2")
                .buildValidator())
            // 校验位
            .addField(new EnumConfigItem("parity", true, "None")
                .displayName("校验位")
                .addOption("None", "无校验")
                .addOption("Odd", "奇校验")
                .addOption("Even", "偶校验")
                .buildValidator())
            // 流控 (value = SerialPort.FLOW_CONTROL_* 常量组合)
            .addField(new EnumConfigItem("flow_control", false, "0")
                .displayName("流控")
                .addOption("0", "无")
                .addOption("17", "RTS/CTS (硬件)")
                .addOption("4352", "DTR/DSR (硬件)")
                .addOption("1114112", "XOn/XOff (软件)")
                .buildValidator())
            // 超时时间
            .addField(new NumericConfigItem("timeout", false, Const.READ_TIMEOUT_MS)
                .displayName("超时时间(ms)")
                .range(100.0, 60000.0));
    }

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

    /**
     * 创建波特率选项
     *
     * @return 波特率选项映射
     */
    private Map<String, String> createBaudRates() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("1200", "1200");
        map.put("2400", "2400");
        map.put("4800", "4800");
        map.put("9600", "9600");
        map.put("19200", "19200");
        map.put("38400", "38400");
        map.put("57600", "57600");
        map.put("115200", "115200");
        return map;
    }
}
