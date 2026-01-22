package com.ecat.integration.SerialIntegration.Listener;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;

/**
 * SerialSourceEventAdapter 适配器类
 * 将 jSerialComm 的事件转换为 SerialDataListener 通知
 *
 * @author coffee
 */
public class SerialSourceEventAdapter implements SerialPortDataListener {

    private static final Log log = LogFactory.getLogger(SerialSourceEventAdapter.class);

    private final List<SerialDataListener> listeners = new CopyOnWriteArrayList<>();
    private final com.ecat.integration.SerialIntegration.SerialSource serialSource;

    /**
     * 创建事件适配器
     *
     * @param serialSource SerialSource 对象
     */
    public SerialSourceEventAdapter(com.ecat.integration.SerialIntegration.SerialSource serialSource) {
        if (serialSource == null) {
            throw new IllegalArgumentException("SerialSource cannot be null");
        }
        this.serialSource = serialSource;
    }

    /**
     * 添加数据监听器
     *
     * @param listener 监听器
     */
    public void addListener(SerialDataListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            log.debug(this.serialSource.getPortName() + " added listener, total listeners: " + listeners.size());
        }
    }

    /**
     * 移除数据监听器
     *
     * @param listener 监听器
     */
    public void removeListener(SerialDataListener listener) {
        if (listener != null && listeners.remove(listener)) {
            log.debug(this.serialSource.getPortName() + " removed listener, total listeners: " + listeners.size());
        }
    }

    /**
     * 移除所有监听器
     */
    public void removeAllListeners() {
        int count = listeners.size();
        listeners.clear();
        log.info(this.serialSource.getPortName() + " removed all listeners, count: " + count);
    }

    /**
     * 获取监听器数量
     *
     * @return 监听器数量
     */
    public int getListenerCount() {
        return listeners.size();
    }

    @Override
    public int getListeningEvents() {
        // jSerialComm 2.6.2 中可能不支持 LISTENING_EVENT_PORT_DISCONNECTED
        // 只监听数据可用事件
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        if (listeners.isEmpty()) {
            return; // 没有监听器，直接返回
        }

        try {
            int eventType = event.getEventType();

            if (eventType == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                // 读取可用数据
                SerialPort serialPort = serialSource.getSerialPort();
                int bytesAvailable = serialPort.bytesAvailable();
                if (bytesAvailable > 0) {
                    byte[] data = new byte[bytesAvailable];
                    int bytesRead = serialPort.readBytes(data, data.length);

                    if (bytesRead > 0) {
                        log.debug(this.serialSource.getPortName() + " received " + bytesRead + " bytes");
                        // 打印16进制数据和ASCII字符串
                        String hex = bytesToHex(data, bytesRead);
                        String ascii = bytesToAscii(data, bytesRead);
                        log.debug(this.serialSource.getPortName() + " data: " + hex + " | ASCII: " + ascii);
                        notifyDataReceived(data, bytesRead);
                    }
                }
            }
            // LISTENING_EVENT_PORT_DISCONNECTED 在当前版本中不可用
            // 可以通过其他方式检测端口断开
        } catch (Exception e) {
            log.error(this.serialSource.getPortName() + " error handling serial event: " + e.getMessage());
            notifyError(e);
        }
    }

    /**
     * 通知所有监听器数据接收事件
     *
     * @param data 接收到的数据
     * @param length 数据长度
     */
    private void notifyDataReceived(byte[] data, int length) {
        for (SerialDataListener listener : listeners) {
            try {
                listener.onDataReceived(data, length);
            } catch (Exception e) {
                log.warn(this.serialSource.getPortName() + " error notifying listener: " + e.getMessage());
                listener.onError(e);
            }
        }
    }

    /**
     * 通知所有监听器错误事件
     *
     * @param ex 异常信息
     */
    private void notifyError(Exception ex) {
        for (SerialDataListener listener : listeners) {
            try {
                listener.onError(ex);
            } catch (Exception e) {
                log.warn(this.serialSource.getPortName() + " error notifying listener of error: " + e.getMessage());
            }
        }
    }

    /**
     * 将字节数组转换为16进制字符串
     */
    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    /**
     * 将字节数组转换为ASCII字符串
     */
    private String bytesToAscii(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (bytes[i] >= 32 && bytes[i] <= 126) {
                // 可打印ASCII字符
                sb.append((char) bytes[i]);
            } else {
                // 控制字符用点号代替
                sb.append('.');
            }
        }
        return sb.toString();
    }
}
