package com.ecat.integration.SerialIntegration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 串口传输通道（comm_settings.transport 的强类型读出）：同一 serial 协议跑两种物理通道
 * ——SERIAL = 本地 COM（jSerialComm），TCP = 串口服务器（RTU over TCP，ECAT 作 TCP 客户端，
 * UART 参数由串口服务器硬件侧配置）。
 *
 * <p>schema 枚举 value（小写 "serial"/"tcp"）与本枚举一一对应；parse 缺 transport 键按
 * SERIAL（schema 默认同源，存量 entry 零迁移）。未知取值抛带原值的 IllegalArgumentException
 * （严格模式：不猜、不静默回退）。
 *
 * @author coffee
 */
public enum SerialTransport {

    /** 本地 COM 口（jSerialComm，UART 参数在本机 fd 上生效）。 */
    SERIAL("serial"),

    /** 串口服务器（RTU over TCP，ECAT 作 TCP 客户端；UART 参数在硬件侧）。 */
    TCP("tcp");

    /** schema 枚举 value（comm_settings.transport 的存储形态）。 */
    private final String value;

    SerialTransport(String value) {
        this.value = value;
    }

    /** schema 枚举 value（comm_settings.transport 的存储形态；与 BaudRate 等域枚举同惯例）。 */
    public String getValue() {
        return value;
    }

    /** schema options 映射（value → 兜底 label；i18n strings.json 的 options 键同 value）。 */
    public static Map<String, String> toMap() {
        Map<String, String> options = new LinkedHashMap<>();
        for (SerialTransport candidate : values()) {
            options.put(candidate.value, fallbackLabel(candidate));
        }
        return options;
    }

    /**
     * comm_settings.transport 存储值 → 枚举（严格：未知取值带原值上抛）。
     *
     * @param value comm_settings.transport 的字符串值（.trim 后精确匹配）
     * @return 对应枚举
     * @throws IllegalArgumentException 取值不支持
     */
    public static SerialTransport fromValue(String value) {
        for (SerialTransport candidate : values()) {
            if (candidate.value.equals(value == null ? null : value.trim())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("comm_settings transport 取值不支持: " + value);
    }

    /** 兜底 label（i18n 缺席时的最终回退，与域枚举 toMap 惯例一致）。 */
    private static String fallbackLabel(SerialTransport candidate) {
        return candidate == SERIAL ? "Serial (local COM)" : "TCP (serial server)";
    }
}
