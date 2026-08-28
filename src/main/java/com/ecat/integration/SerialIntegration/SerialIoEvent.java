package com.ecat.integration.SerialIntegration;

import lombok.Getter;

/**
 * 串口入站帧事件载荷（arch-review 29 号 M3-P1，W2-1 定稿形态）：Transport（sweeper/监听器层）
 * → SerialIoPool per-port 串行视图投递的 serial 载荷（诊断词汇：requestId/资源键）。
 *
 * <p>为什么是完整帧而非散字节：帧定界留在 IO 侧（铁律 1「只读字节+组帧+投事件」，
 * checkResponseFunction 是设备注入的纯函数，执行微秒级），投递粒度=一帧一事件——避免每 25ms
 * 零散字节灌爆端口队列。事件执行体（Runnable）闭包捕获 responseFuture 等 finalize 上下文，
 * 故本载荷不携带 context 引用（03 §10-6 倾向闭包捕获，少一个公开字段）。
 *
 * <p>不可变：frameBytes 构造与读取双向防御性拷贝。
 *
 * @author coffee
 */
@Getter
public final class SerialIoEvent {

    /** 端口资源键 "serial-io:{port}"（诊断词汇；互斥本体=域池 per-port 视图）。 */
    private final String resourceKey;

    /** 串口的"连接"身份位 = portName（对齐 tcp-connection:{connId} 的身份语义）。 */
    private final String connId;

    /** 已定界的完整响应帧（IO 线程完成组帧后的产物，非原始散字节）。 */
    private final byte[] frameBytes;

    /** 事件驱动 requestId："io-serial-{port}-{seq}"（契约 §2.3；seq 为 per-port AtomicLong）。 */
    private final String requestId;

    public SerialIoEvent(String resourceKey, String connId, byte[] frameBytes, String requestId) {
        this.resourceKey = resourceKey;
        this.connId = connId;
        this.frameBytes = frameBytes != null ? frameBytes.clone() : new byte[0];
        this.requestId = requestId;
    }

    /** frameBytes 防御性拷贝出口（不可变契约）。 */
    public byte[] getFrameBytes() {
        return frameBytes.clone();
    }
}
