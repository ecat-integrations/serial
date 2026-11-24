package com.ecat.integration.SerialIntegration;

/**
 * 串口事务优先级（公开测试缝参数类型）：{@code SerialSource#submitTransaction} 的
 * 优先级形参类型——public 仅为消费仓测试的 Mockito 打桩可命名（原形参为执行者内部
 * 枚举，包外不可命名致打桩无法编译；bugs/bug-record-20261124-205000 债 2）。
 *
 * <p>语义映射执行者内部对话优先级：{@link #HIGH}=命令/写对话（队列排队语义「最终要
 * 执行」，满且无可挤时同步拒绝）；{@link #NORMAL}=周期轮询对话（预算耗尽/队满=真弃轮
 * 记账跳过）。生产代码不经本类型直接提交——一律走 {@link SerialTransactionStrategy}
 * 两入口（executeWithLambda=HIGH / executePolling=NORMAL），优先级选择由入口语义承载。
 *
 * @author coffee
 */
public enum SerialPriority {
    /** 命令/写事务：高优先级对话（等锁预算内未授予即失败，队满同步抛出）。 */
    HIGH,
    /** 周期轮询事务：普通优先级对话（预算耗尽/队满=真弃轮，LockBusySkippedException 内化）。 */
    NORMAL
}
