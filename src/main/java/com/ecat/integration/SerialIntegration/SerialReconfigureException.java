package com.ecat.integration.SerialIntegration;

/**
 * reconfigure 端口重建命令执行失败/超时异常（阶段② reconfigure 入队新增，
 * Actor 事务准入设计 §3 选项 a）。
 *
 * <p>触发场景：物理参数变化的 reconfigure 已把重建命令排入本口命令队列，但未在
 * 有界等待预算内获得执行权（预算耗尽或队满显式拒绝）——典型根因是前方事务长期
 * 在飞（如挂死写等待硬超时/幽灵锁收割）。此前的重建是无锁协调的即时 close+reopen
 * （历史竞态形态）；入队后「等不到事务完成」必须作为显式同步失败上抛给 core 分诊链
 * （EntryNotificationException 同步语义保持），不得吞掉或猜测兜底。
 *
 * <p>设计：unchecked（继承 RuntimeException）——register 链路无 checked 声明面，
 * 与 {@link SerialWriteException} 同风格。异常抛出时端口状态原样未动
 * （未 close、serialInfo 未替换），调用方可重试或走 disable/enable 兜底。
 *
 * @author coffee
 */
public class SerialReconfigureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SerialReconfigureException(String message) {
        super(message);
    }
}
