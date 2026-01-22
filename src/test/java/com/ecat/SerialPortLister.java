package com.ecat;

import com.fazecast.jSerialComm.SerialPort;

public class SerialPortLister {
    /**
     * 列出本机所有可用的串口信息，包含详细信息
     */
    public static void listSerialPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        
        if (ports.length == 0) {
            System.out.println("未检测到可用的串口设备。");
            return;
        }
        
        System.out.println("检测到 " + ports.length + " 个串口设备：");
        System.out.println("+-----------------------------------------+");
        System.out.printf("| %-15s | %-25s |%n", "串口名称", "描述信息");
        System.out.println("+-----------------------------------------+");
        
        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            String description = port.getPortDescription();
            
            // 处理可能的空值
            if (description == null || description.trim().isEmpty()) {
                description = "未提供描述";
            }
            
            System.out.printf("| %-15s | %-25s |%n", 
                    portName, 
                    truncateString(description, 25));
            
            System.out.println("+-----------------------------------------+");
        }
    }
    
    /**
     * 截断字符串并添加省略号
     */
    private static String truncateString(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * 主方法：打印所有可用的串口信息
     */
    public static void main(String[] args) {
        listSerialPorts();
    }
}