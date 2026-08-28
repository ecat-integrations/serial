package com.ecat.integration.SerialIntegration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Utils.Mdc.TraceContext;

/**
 * fake-tier SDK 定时缝替身（serial 域，镜像 http S0 的 FakeSdkTimers 形态）：构造即经
 * {@link SerialSdkTimers#bindForTest} 绑定，捕获 SDK 提交的全部「MDC 包装单发」（周期链
 * 每一拍 / 命令间 delay / 事务硬超时执法），由测试线程手动驱动——零后台线程、零真实时钟，
 * 断言以「捕获的调用记录」表达（测试纪律：禁 sleep 同步）。SDK 若未走 SerialSdkTimers
 * 提交，捕获列表为空即失败——定时接线本身在测试面内。
 *
 * <p>每条记录持有提交时 MDC 快照（坐标传播断言面）；{@link #fire(int)} 在测试线程执行
 * 已包装命令（提交时上下文被 wrapRunnable 恢复，MDC 断言由此成立）。</p>
 *
 * <p>{@link #close()} 必须 unbind：缝是静态绑定，测试须成对清理防跨测试泄漏。</p>
 */
final class FakeSerialTimers implements SerialSdkTimers.ShotScheduler, AutoCloseable {

    /** 单发提交记录：命令（已含 MDC 包装）/延迟毫秒/可取消桩/提交时 MDC 快照。 */
    static final class Shot {
        final Runnable command;
        final long delayMillis;
        final StubFuture future = new StubFuture();
        final Map<String, String> submitMdc;

        Shot(Runnable command, long delayMillis, Map<String, String> submitMdc) {
            this.command = command;
            this.delayMillis = delayMillis;
            this.submitMdc = submitMdc;
        }
    }

    final List<Shot> shots = new CopyOnWriteArrayList<>();

    FakeSerialTimers() {
        SerialSdkTimers.bindForTest(this);
    }

    @Override
    public ScheduledFuture<?> fireAfter(Runnable command, long delayMillis) {
        Shot shot = new Shot(command, delayMillis, TraceContext.capture());
        shots.add(shot);
        return shot.future;
    }

    @Override
    public void close() {
        SerialSdkTimers.unbindForTest();
    }

    /** 手动触发第 i 个单发（模拟到拍；命令在测试线程以提交时 MDC 执行）。 */
    void fire(int index) {
        shots.get(index).command.run();
    }

    /** 最近一条提交记录。 */
    Shot lastShot() {
        return shots.get(shots.size() - 1);
    }

    /** 可取消的最小 ScheduledFuture 桩：只承载 cancel/isCancelled 语义。 */
    static final class StubFuture implements ScheduledFuture<Object> {
        volatile boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("测试桩不支持取值");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("测试桩不支持取值");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
