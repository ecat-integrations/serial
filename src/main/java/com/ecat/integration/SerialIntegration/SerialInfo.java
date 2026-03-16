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
     * 比较端口通信设置是否一致（不包含 timeout）
     */
    public boolean settingsMatch(SerialInfo other) {
        if (other == null) return false;
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
                '}';
    }
}
