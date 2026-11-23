package com.ecat.integration.SerialIntegration;

/**
 * 端口对话队列满的显式拒绝信号（serial IO 最小模型 §3.5）。
 *
 * <p>每口的串行执行者持一条<b>有界</b>优先级等待队列（默认容量 3，每端口可配置）：
 * 队列满时新到达的对话按入口语义处置——写/命令入口（executeWithLambda）在提交线程
 * <b>同步抛出</b>本异常（背压显式可见，调用方按失败处理）；轮询入口（executePolling）
 * 以本异常异常完成其返回 future，落 {@link LockBusySkippedException} 的既有
 * isLockBusySkip 内化路径（周期链跳过本轮，下周期再试）。
 *
 * <p>继承 {@link LockBusySkippedException} 是语义声明：队列满是「本轮拿不到口」的
 * 跳过类信号，不是传输/设备错误——所有既有把锁忙识别为跳过的消费方
 * （SerialPolling.settleRound、各仓 exceptionally 样板）沿继承链自然命中，零改码。
 *
 * <p>高优先级到达且队满时挤掉最新排队的普通对话，被挤者的 future 同样以本异常完成；
 * 只有「满且无可挤」时才抛向提交线程。
 *
 * @author coffee
 */
public class PortQueueFullException extends LockBusySkippedException {

    private static final long serialVersionUID = 1L;

    public PortQueueFullException(String message) {
        super(message);
    }
}
