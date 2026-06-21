package com.ecat.integration.SerialIntegration;

/**
 * 串口写路径失败异常（非阻塞写升级新增，由 {@link SerialSourcePort#asyncSendData(byte[])} 抛出）。
 *
 * <p>触发场景：
 * <ol>
 *   <li>writeBytes 返回 &lt; 0：非阻塞模式下 EAGAIN/反压，jSerialComm 随即关闭端口（设计文档 §B.6 实证）。</li>
 *   <li>写前自动 reopen 失败：端口不可用（如 ttyUSB 被拔除），openPort 重开仍失败。</li>
 * </ol>
 *
 * <p>设计：unchecked（继承 RuntimeException）。asyncSendData 的 exceptionally 做类型透传——
 * 本异常会原样作为返回 future 的失败原因：调用方链 .exceptionally 直接收到本类型；
 * 调用方 .get() 时位于 ExecutionException#getCause 内。
 *
 * <p>不重试同一帧（用户决策：对端设备状态未知，重试无意义），由调用方决定后续处理（如标记离线、上层告警）。
 *
 * @author coffee
 */
public class SerialWriteException extends RuntimeException {

    public SerialWriteException(String message) {
        super(message);
    }

    public SerialWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
