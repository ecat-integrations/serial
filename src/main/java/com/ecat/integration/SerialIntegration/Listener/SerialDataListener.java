package com.ecat.integration.SerialIntegration.Listener;

/**
 * 串口数据监听器接口
 * 用于接收中断驱动的数据通知
 *
 * @author coffee
 */
public interface SerialDataListener {

    /**
     * 当接收到数据时调用
     *
     * @param data 接收到的数据
     * @param length 数据长度
     */
    void onDataReceived(byte[] data, int length);

    /**
     * 当发生错误时调用
     *
     * @param ex 异常信息
     */
    void onError(Exception ex);
}
