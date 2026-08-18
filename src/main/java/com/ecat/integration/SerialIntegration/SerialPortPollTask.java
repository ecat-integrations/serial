package com.ecat.integration.SerialIntegration;

import java.util.concurrent.ScheduledFuture;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

/**
 * 单端口串口读轮询任务（IO 线程收敛 P1：jSerialComm 事件模式 → 轮询模式）。
 *
 * <p>应用场景：替代 jSerialComm {@code addDataListener}——事件模式下每打开一个串口，
 * 库内就产生一根未命名的 {@code waitForEvent} 事件线程（生产实测 48 口 = 48 条，其中 16 条
 * modbus RTU 口的事件线程在 adapter 永久 pause 后纯属闲置）。轮询模式下每个已打开端口在
 * 共享调度器上挂一个 {@link SerialSourcePort#POLL_PERIOD_MS} 周期的 fixedDelay 任务，线程账归零。
 *
 * <p>任务体极轻（热路径自证）：空闲时 = 一次 volatile paused 读 + 一次
 * {@code bytesAvailable()} 系统调用即返回，无数据零分配；有数据才分配缓冲并走既有
 * {@link SerialSourcePort#handleIncomingData} 管线（共享接收缓冲 + 通知全部 SerialSource，
 * 与旧事件路径同一入口，应用层监听器零感知）。
 *
 * <p>生命周期与端口 open/close 对称：openPort 每次成功打开挂新任务（先取消旧任务防重复），
 * closePort 显式取消与 {@code bytesAvailable() == -1} 哨兵自取消任一先到即停。
 * 本类为具名类（非 lambda），承载线程（serial-io-sweeper / 测试注入）日志可归属。
 *
 * <p>严格模式边界（IO 线程收敛调研 B.2 spike 已实证，非猜测兜底）：
 * <ul>
 *   <li>{@code bytesAvailable() == -1} 是 jSerialComm 对已关端口的哨兵返回值——自取消是
 *       显式生命周期语义；</li>
 *   <li>轮询读异常（如 USB 口被拔的传输层异常）捕获后记告警并放行下一 tick：不能外抛，
 *       scheduleWithFixedDelay 的引擎/STPE 契约均为「未捕获异常即永久停止调度」，外抛等于
 *       用一个瞬时异常杀死整口轮询。下一 tick 复查 {@code bytesAvailable()}，口真死则
 *       -1 哨兵自取消，不会无限轮询死口。</li>
 * </ul>
 *
 * @author coffee
 */
final class SerialPortPollTask implements Runnable {

    private static final Log log = LogFactory.getLogger(SerialPortPollTask.class);

    private final SerialSourcePort port;

    /**
     * 调度器返回的自身句柄，-1 哨兵自取消用。
     * scheduleWithFixedDelay 返回后立即绑定，早于首次触发（initialDelay = 周期）。
     */
    private volatile ScheduledFuture<?> self;

    SerialPortPollTask(SerialSourcePort port) {
        this.port = port;
    }

    /** 绑定调度句柄（提交方在 {@code scheduleWithFixedDelay} 返回后立即调用）。 */
    void bindHandle(ScheduledFuture<?> handle) {
        this.self = handle;
    }

    @Override
    public void run() {
        // paused（Modbus 直持 InputStream 期间）优先于一切：一次 volatile 读不碰 syscall，
        // 数据留在 OS 缓冲由 Modbus 自己读——与旧事件适配器 pause 语义 1:1 平移。
        if (port.isPollPaused()) {
            return;
        }
        int available;
        try {
            available = port.serialPort.bytesAvailable();
        } catch (Exception e) {
            log.warn("[{}] poll bytesAvailable failed: {} (retry next tick; self-cancel via -1 sentinel if port closed)",
                    port.getPortName(), e.getMessage());
            return;
        }
        if (available < 0) {
            // -1 哨兵：口已关（closePort / 写反压自动关）。自取消避免反复轮询死口；
            // 重开时 openPort 会挂全新任务。
            ScheduledFuture<?> handle = self;
            if (handle != null) {
                handle.cancel(false);
            }
            log.info("[POLL-STOP] port={}, bytesAvailable={} (port closed), poll task self-cancelled",
                    port.getPortName(), available);
            return;
        }
        if (available == 0) {
            return; // 空闲热路径：一次系统调用返回，零分配
        }
        byte[] buffer = new byte[available];
        int read = port.serialPort.readBytes(buffer, available);
        if (read > 0) {
            port.handleIncomingData(buffer, read);
        }
    }
}
