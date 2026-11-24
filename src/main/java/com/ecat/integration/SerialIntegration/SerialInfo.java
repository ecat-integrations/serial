package com.ecat.integration.SerialIntegration;

import java.util.Objects;

/**
 * SerialInfo is a class that holds the configuration details
 * for a serial port connection, including port name, baud rate,
 * data bits, stop bits, and parity.
 * 
 * @author coffee
 */
public class SerialInfo {
    // 串口号
    String portName;
    // 波特率
    Integer baudrate;
    // 数据位
    Integer dataBits;
    // 停止位
    Integer stopBits;
    // 奇偶校验位
    Integer parity;
    // 流控 (0 = disabled, 使用 SerialPort.FLOW_CONTROL_* 常量 OR 组合)
    int flowControl;
    // 超时时间（毫秒），默认 500
    int timeout = 500;
    // 传输通道（默认 SERIAL——存量构造路径零迁移）
    SerialTransport transport = SerialTransport.SERIAL;
    // 串口服务器地址（TCP 档；portName 即 host:port 派生串，注册表键复用）
    String tcpHost;
    // 串口服务器端口（TCP 档）
    Integer tcpPort;

    public SerialInfo(String portName, Integer baudrate, Integer dataBits, Integer stopBits, Integer parity) {
        this(portName, baudrate, dataBits, stopBits, parity, 0);
    }

    public SerialInfo(String portName, Integer baudrate, Integer dataBits, Integer stopBits, Integer parity, int flowControl) {
        this(portName, baudrate, dataBits, stopBits, parity, flowControl, 500);
    }

    public SerialInfo(String portName, Integer baudrate, Integer dataBits, Integer stopBits, Integer parity, int flowControl, int timeout) {
        this.portName = portName;
        this.baudrate = baudrate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.flowControl = flowControl;
        this.timeout = timeout;
    }

    /**
     * 比较端口通信设置是否一致（不包含 timeout）。
     * 跨传输通道必不匹配；TCP 档物理身份只由 host/port 构成（UART 参数在串口服务器硬件侧，
     * 本机不持有）；SERIAL 档比既有五参数 + 流控，语义不变。
     */
    public boolean settingsMatch(SerialInfo other) {
        if (other == null) return false;
        if (transport != other.transport) return false;
        if (transport == SerialTransport.TCP) {
            return Objects.equals(this.tcpHost, other.tcpHost)
                    && Objects.equals(this.tcpPort, other.tcpPort);
        }
        return Objects.equals(this.portName, other.portName)
                && Objects.equals(this.baudrate, other.baudrate)
                && Objects.equals(this.dataBits, other.dataBits)
                && Objects.equals(this.stopBits, other.stopBits)
                && Objects.equals(this.parity, other.parity)
                && this.flowControl == other.flowControl;
    }

    /**
     * 返回通信设置的描述字符串，用于日志输出
     */
    public String settingsDescription() {
        if (transport == SerialTransport.TCP) {
            return "transport=tcp, port=" + portName
                    + ", tcpHost=" + tcpHost
                    + ", tcpPort=" + tcpPort;
        }
        return "port=" + portName
                + ", baudrate=" + baudrate
                + ", dataBits=" + dataBits
                + ", stopBits=" + stopBits
                + ", parity=" + parity
                + ", flowControl=" + flowControl;
    }

    @Override
    public String toString() {
        return "SerialInfo{" +
                "portName='" + portName + '\'' +
                ", baudrate=" + baudrate +
                ", dataBits=" + dataBits +
                ", stopBits=" + stopBits +
                ", parity=" + parity +
                ", flowControl=" + flowControl +
                ", timeout=" + timeout +
                ", transport=" + transport +
                ", tcpHost=" + tcpHost +
                ", tcpPort=" + tcpPort +
                '}';
    }
}
