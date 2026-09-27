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

package com.ecat.integration.SerialIntegration;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Map;

/**
 * comm_settings 类型化读端：裸 Map → {@link SerialInfo}。
 *
 * <p>设备集成侧唯一合法读出通道（与 {@link ConfigFlows.SerialCommFlow} 写端同仓同源演进，
 * comm_settings 形状变更对采纳仓爆炸半径为零）。严格模式：key 缺失/类型不符/枚举值不支持
 * 抛带字段名的 IllegalArgumentException，不吞、不返回 null 掩盖。
 *
 * @author coffee
 */
public final class SerialCommSettings {

    private SerialCommSettings() {
    }

    /**
     * @param entryData ConfigEntry.data（含 comm_settings 整块=扁平 7 字段）
     * @return 7 参构造的 SerialInfo——timeout/flow_control 真实传入（不再被设备侧解析丢弃）
     */
    public static SerialInfo parse(Map<String, Object> entryData) {
        Map<String, Object> comm = map(entryData, "comm_settings");
        // timeout/flow_control 是 not-required 字段（表单可不提交），缺省与 schema 预填同源
        int timeout = comm.containsKey("timeout")
                ? toInt(comm, "timeout") : Const.READ_TIMEOUT_MS;
        return new SerialInfo(
                str(comm, "serial_port"),
                toInt(comm, "baudrate"),
                toInt(comm, "data_bits"),
                toStopBits(numericEnumValue(comm, "stop_bits")),
                toParity(str(comm, "parity")),
                toFlowControl(comm.containsKey("flow_control")
                        ? numericEnumValue(comm, "flow_control") : "0"),
                timeout);
    }

    /**
     * 数值编码枚举字段的规范化读取：整数值的浮点字符串形态（"1.0"）规范化为整数字符串（"1"），
     * 非整数值抛错——容忍的是表示形态不是取值本身（"1.5" 停止位是真实 UART 档位，静默取整会错位）。
     * schema 枚举 value 本身是整数字符串（"1"/"2"、"0"/"17"/...），API 消费方往返可能带出浮点形态。
     */
    private static String numericEnumValue(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            throw new IllegalArgumentException("comm_settings 字段缺失: " + key);
        }
        double d;
        try {
            d = Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("comm_settings 字段不是数值: " + key + "=" + v);
        }
        if (Math.round(d) != d) {
            throw new IllegalArgumentException("comm_settings 字段必须是整数值: " + key + "=" + v);
        }
        return String.valueOf((long) d);
    }

    /** schema 枚举 "1"/"2" → jSerialComm 停止位常量（TWO_STOP_BITS=3 而非 2，必须显式映射）。 */
    private static int toStopBits(String v) {
        switch (v) {
            case "1": return SerialPort.ONE_STOP_BIT;
            case "2": return SerialPort.TWO_STOP_BITS;
            default: throw new IllegalArgumentException("comm_settings stop_bits 取值不支持: " + v);
        }
    }

    /**
     * schema 枚举 "None"/"Odd"/"Even"（首字母大写，serial 域 value）→ jSerialComm 校验常量。
     * 注意与 modbus 库 schema（NONE/ODD/EVEN）不同源——两个库的枚举 value 大小写就是不同的。
     */
    private static int toParity(String v) {
        switch (v) {
            case "None": return SerialPort.NO_PARITY;
            case "Odd": return SerialPort.ODD_PARITY;
            case "Even": return SerialPort.EVEN_PARITY;
            default: throw new IllegalArgumentException("comm_settings parity 取值不支持: " + v);
        }
    }

    /** schema 枚举 value 本身即 SerialPort.FLOW_CONTROL_* 常量组合值，字符串→int 直转；未知值抛错。 */
    private static int toFlowControl(String v) {
        switch (v) {
            case "0": case "17": case "4352": case "1114112":
                return Integer.parseInt(v);
            default: throw new IllegalArgumentException("comm_settings flow_control 取值不支持: " + v);
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            throw new IllegalArgumentException("comm_settings 字段缺失: " + key);
        }
        return v.toString();
    }

    /**
     * 数值解析容忍浮点字符串（"500.0"→500）：schema defaultValue 是数值型，API 消费方会把默认值
     * 字符串化——Integer.parseInt 不认浮点（gassensor 曾因此半死 entry，收编为唯一实现）。
     */
    private static int toInt(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            throw new IllegalArgumentException("comm_settings 字段缺失: " + key);
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(v).trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("comm_settings 字段不是数值: " + key + "=" + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("comm_settings 字段缺失或不是对象: " + key);
        }
        return (Map<String, Object>) v;
    }
}
