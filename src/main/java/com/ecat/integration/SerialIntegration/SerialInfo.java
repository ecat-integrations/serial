package com.ecat.integration.SerialIntegration;

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

    public SerialInfo(String portName, Integer baudrate, Integer dataBits, Integer stopBits, Integer parity) {
        this.portName = portName;
        this.baudrate = baudrate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
    }

    @Override
    public String toString() {
        return "SerialInfo{" +
                "portName='" + portName + '\'' +
                ", baudrate=" + baudrate +
                ", dataBits=" + dataBits +
                ", stopBits=" + stopBits +
                ", parity=" + parity +
                '}';
    }
}
