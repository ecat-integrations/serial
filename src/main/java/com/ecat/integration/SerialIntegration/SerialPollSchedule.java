package com.ecat.integration.SerialIntegration;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.ecat.core.Task.runner.FireDecision;
import com.ecat.core.Task.runner.RoundSchedule;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * serial 轮询网格策略（29 号 v2 S1：网格知识归域，core PeriodicChain 只按链事件取延迟；
 * 实现形态照 httpserver PollingSchedule 域侧实现）。内部以注入纳米钟维护名义锚点：
 * <ul>
 *   <li>fixedDelay（serial 域默认，存量设备仓全为此形态）：结算点+period=下一拍
 *       （完成点重排，天然防重叠；锁忙/熔断跳过轮瞬时结算，网格不变）；</li>
 *   <li>fixedRate：名义网格+period 推进，在飞期间跨过的拍跳过（推进到首个未来网格点，
 *       不做滞后补跑——补跑=同任务重入，正是要消灭的形态；semeatech/gassensor 既有
 *       scheduleAtFixedRate 节律的等价承载）；</li>
 *   <li>到拍滞后超过一个整周期→过期即弃（调度三原则）：本轮不触碰轮体，锚点按
 *       skips = lag / period + 1 推进到首个未来网格点后重排；</li>
 *   <li>首发延迟=initialDelay（默认 0=立即首轮，与全部存量设备仓一致；santak/teledyne-api
 *       等上电就绪窗的原生承载）。</li>
 * </ul>
 * 锚点仅链上串行读写（单拍在飞时不被触碰，无跨线程竞态）。
 */
final class SerialPollSchedule implements RoundSchedule {

    private static final Log log = LogFactory.getLogger(SerialPollSchedule.class);

    private final boolean fixedRate;
    private final long periodNanos;
    private final long initialDelayMs;
    private final LongSupplier nanoClock;
    private final String portName;

    /** 名义网格锚点（nanoClock 基，起链时刻锚定）。 */
    private long nominalNanos;

    private SerialPollSchedule(boolean fixedRate, long periodNanos, long initialDelayMs,
            LongSupplier nanoClock, String portName) {
        this.fixedRate = fixedRate;
        this.periodNanos = periodNanos;
        this.initialDelayMs = initialDelayMs;
        this.nanoClock = nanoClock;
        this.portName = portName;
        this.nominalNanos = nanoClock.getAsLong();
    }

    /** fixedDelay 语义：完成点+period=下轮（serial 域默认形态）。 */
    static SerialPollSchedule fixedDelay(long periodMs, long initialDelayMs, LongSupplier nanoClock,
            String portName) {
        return new SerialPollSchedule(false, requirePositive(periodMs), initialDelayMs, nanoClock, portName);
    }

    /** fixedRate 语义：名义网格发射、到拍在飞跳拍（scheduleAtFixedRate 节律等价承载）。 */
    static SerialPollSchedule fixedRate(long periodMs, long initialDelayMs, LongSupplier nanoClock,
            String portName) {
        return new SerialPollSchedule(true, requirePositive(periodMs), initialDelayMs, nanoClock, portName);
    }

    private static long requirePositive(long periodMs) {
        if (periodMs <= 0L) {
            throw new IllegalArgumentException("period 必须为正（毫秒）: " + periodMs);
        }
        return TimeUnit.MILLISECONDS.toNanos(periodMs);
    }

    @Override
    public long firstDelayMillis() {
        return initialDelayMs;
    }

    @Override
    public FireDecision onFire() {
        long now = nanoClock.getAsLong();
        long lag = now - nominalNanos;
        if (lag <= periodNanos) {
            return FireDecision.run();
        }
        // 过期即弃（原引擎 SCHED_STALE_DROP 同语义）：到拍滞后超过一个周期，本轮数据无意义
        long skips = lag / periodNanos + 1L;
        nominalNanos += skips * periodNanos;
        log.info("[serial-polling] port={} 到拍滞后超过一个周期（{}ms），本轮丢弃重排下一周期",
                portName, lag / 1_000_000L);
        return FireDecision.drop(delayMillis(now));
    }

    @Override
    public long onSettleRearmMillis() {
        long now = nanoClock.getAsLong();
        if (!fixedRate) {
            nominalNanos = now + periodNanos;
        } else {
            nominalNanos += periodNanos;
            if (nominalNanos <= now) {
                long skips = (now - nominalNanos) / periodNanos + 1L;
                nominalNanos += skips * periodNanos;
            }
        }
        return delayMillis(now);
    }

    private long delayMillis(long now) {
        return Math.max(0L, (nominalNanos - now) / 1_000_000L);
    }
}
