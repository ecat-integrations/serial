package com.ecat.integration.SerialIntegration;

/**
 * 推模型 RX 的字节投递端（PortTransport 缝内接口）：传输读线程把到达字节交给本接口，
 * 实现只做事件投递（执行者 submitBytes 入口），不解析、不持锁、不阻塞——
 * {@link PortTransport} 线程契约 1。
 *
 * @author coffee
 */
interface ReadSink {

    /**
     * 到达字节投递（在传输读线程上调用）。
     *
     * @param buffer 本次读到的数据
     * @param length 有效长度
     */
    void onBytes(byte[] buffer, int length);
}
