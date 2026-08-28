package com.ecat.integration.SerialIntegration;

/**
 * 轮询事务因「源锁忙」被立即放弃的信号——<b>过渡壳</b>（W2a 切换，单一语义源迁移）。
 *
 * <p>语义权威已下沉 core：{@code com.ecat.core.Task.LockBusySkippedException}
 * （L2-L2 横向共用件，17 号 §1——modbus→serial 共用件批 0 下沉）。本类继承权威版，
 * 消费面零破坏：现存消费仅剩 main 2 处（xian-lechi {@code XianLechiDeviceBase} 的
 * import + thermofisher {@code Air1405fDevice} 的 FQN 调用）及 2 个测试文件
 * （sailhero {@code PollingLockBusySkipTest} / thermofisher {@code AkWriteGateTest}），
 * 均不改一行继续编译且判定结果一致（instanceof 沿继承链命中权威版）。
 *
 * <p><b>收口路径（17 号 §7 收口五要素）</b>：批 1–5 迁移后残留的 {@code isLockBusySkip}
 * 消费样板只剩上述 4 处——批 6 收口改为直引 core 权威版后本壳即可删除
 * （serial/modbus 事务入口直接抛/判 core 权威版）。在此之前本类保持 public。
 *
 * <p>语义本体（产生条件/消费方式）见权威版 Javadoc；{@link #isLockBusySkip(Throwable)}
 * 为静态委托（静态方法无继承分派，残留消费方的显式类名调用形态依赖此桥）。
 *
 * @author coffee
 */
public class LockBusySkippedException
        extends com.ecat.core.Task.LockBusySkippedException {

    private static final long serialVersionUID = 1L;

    public LockBusySkippedException(String message) {
        super(message);
    }

    /**
     * 判定给定异常是否为「本轮跳过」信号——委托 core 权威版实现（单一语义源，
     * 剥包逻辑不双份漂移）。
     */
    public static boolean isLockBusySkip(Throwable t) {
        return com.ecat.core.Task.LockBusySkippedException.isLockBusySkip(t);
    }
}
