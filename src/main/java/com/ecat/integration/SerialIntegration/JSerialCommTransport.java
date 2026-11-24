package com.ecat.integration.SerialIntegration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.ecat.core.Task.NamedThreadFactory;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.fazecast.jSerialComm.SerialPort;

/**
 * {@link PortTransport} 的本地 COM（jSerialComm 2.9.3）实现：承载 SerialSourcePort 的
 * 物理段——fd 打开参数链、跨线程关闭、写反压死亡上报、内核 RX 排空，以及推模型 RX
 * 读线程（TIMEOUT_READ_SEMI_BLOCKING 阻塞读，数据到达才唤醒，空闲零系统调用空转）。
 *
 * <p><b>超时模式（实测定档）</b>：读路径 {@code TIMEOUT_READ_SEMI_BLOCKING}（native
 * poll + read，空闲口线程 CPU 0ms，见 RX 模型事实核查 §2）；写路径沿用同一模式字段
 * ——实测（socat PTY 对 + strace）满缓冲下写调用在各模式下都不快速返回（NONBLOCKING
 * 下 native 写循环对 EAGAIN 自旋），恢复通道都是事务硬超时看门狗的跨线程 closePort：
 * portHandle 归零后 Java 写循环退出返 -1，死亡通知经
 * {@link #onTransportDead(Consumer)} 上报执行者。该链路在 NONBLOCKING 与
 * SEMI_BLOCKING 下等价，写语义保持不变。
 *
 * <p><b>已关哨兵与死亡形态（实测）</b>：{@code bytesAvailable() == -1} 与
 * {@code readBytes() == -1} 均为「口已关」哨兵；PTY 对端消失时阻塞读在一次读超时窗内
 * 返 -1（非异常）——两条死亡形态（返 -1 / 抛异常）都收敛到死亡通知恰一次。
 *
 * <p>SerialPort 对象的存储点与测试注入点在 {@link SerialSourcePort#serialPort}
 * （包内字段；mock 口直赋后不经 {@link #open()} 即被写/排空路径使用，与既有测试
 * 注入形态一致）。
 *
 * @author coffee
 */
final class JSerialCommTransport implements PortTransport {

    private static final Log log = LogFactory.getLogger(JSerialCommTransport.class);

    /** 推模型 RX 单次读块大小：内核每次返回 min(可用量, 本值)，按到达节奏分片投递。 */
    private static final int RX_READ_CHUNK = 1024;

    /** 内核 RX 排空的最大轮数（每轮读空当次 bytesAvailable；超轮仍有数据=对端回灌，如实上报）。 */
    private static final int MAX_RX_DRAIN_ROUNDS = 64;

    private final SerialSourcePort port;

    /** 死亡通知接收端（open 前经 {@link #onTransportDead(Consumer)} 注册；未注册时死亡不上报）。 */
    private volatile Consumer<String> deathHandler;

    /** 死亡通知「恰一次」守卫：每次成功打开重置（一场生死周期恰一次通知）。 */
    private final AtomicBoolean deadNotified = new AtomicBoolean();

    /** 推模型 RX 读循环；null = 未启动/已退出（volatile：start/stop 调用线程与读线程互见）。 */
    private volatile RxLoop rxLoop;

    JSerialCommTransport(SerialSourcePort port) {
        this.port = port;
    }

    @Override
    public boolean open() {
        SerialInfo info = port.serialInfo;
        SerialPort candidate;
        try {
            candidate = SerialPort.getCommPort(info.portName);
        } catch (RuntimeException openRejected) {
            // 口描述符无效（getCommPort 拒绝假口名/非法路径）：如实记账、端口保持关闭——
            // 与「口不存在时 openPort 返 false」同一终态；重开由写路径自动重开按需再试
            port.serialPort = null;
            log.error("[OPEN FAILED] port={}, 口对象创建被拒（{}）", info.portName, openRejected.getMessage());
            return false;
        }
        port.serialPort = candidate;
        candidate.setBaudRate(info.baudrate);
        candidate.setNumDataBits(info.dataBits);
        candidate.setNumStopBits(info.stopBits);
        candidate.setParity(info.parity);
        candidate.setFlowControl(info.flowControl);
        // 读：SEMI_BLOCKING 阻塞于内核 poll（readTimeout=会话超时档）；写：满缓冲恢复链
        // 看门狗跨线程强拆，模式字段读/写共用不影响该语义（类注释实测定档）。
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs(), 0);
        if (!candidate.openPort()) {
            log.error("[OPEN FAILED] port={}, baudrate={}, dataBits={}, stopBits={}, parity={}",
                    info.portName, info.baudrate, info.dataBits, info.stopBits, info.parity);
            return false;
        }
        deadNotified.set(false);
        return true;
    }

    /** 读超时档：会话超时为正用之，否则退回默认常量档（SerialInfo 直构造形态可为 0/负）。 */
    private int readTimeoutMs() {
        SerialInfo info = port.serialInfo;
        return info.timeout > 0 ? info.timeout : Const.READ_TIMEOUT_MS;
    }

    @Override
    public boolean close() {
        SerialPort serialPort = port.serialPort;
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            return true;
        }
        return false;
    }

    @Override
    public boolean isOpen() {
        SerialPort serialPort = port.serialPort;
        return serialPort != null && serialPort.isOpen();
    }

    @Override
    public int write(byte[] buffer, int length) {
        int written = port.serialPort.writeBytes(buffer, length);
        if (written < 0) {
            notifyTransportDead("write-failed(" + written + ")");
        }
        return written;
    }

    @Override
    public int read(byte[] buffer, int length) {
        return port.serialPort.readBytes(buffer, length);
    }

    @Override
    public int available() {
        return port.serialPort.bytesAvailable();
    }

    // ==================== 推模型 RX（读线程） ====================

    @Override
    public void startReading(ReadSink sink) {
        if (rxLoop != null && rxLoop.alive()) {
            return; // 幂等（契约 5）：读线程在跑则重复 start 无副作用
        }
        RxLoop loop = new RxLoop(sink, port.serialInfo.portName);
        rxLoop = loop;
        loop.start();
        log.info("[RX-START] port={}, thread={}", port.serialInfo.portName, loop.threadName);
    }

    @Override
    public void stopReading() {
        RxLoop loop = rxLoop;
        if (loop != null) {
            loop.stop();
        }
    }

    /** 死亡通知（恰一次守卫）：读死亡/写死亡/回调异常统一收敛，接收方幂等处理（契约 4）。 */
    private void notifyTransportDead(String reason) {
        if (!deadNotified.compareAndSet(false, true)) {
            return;
        }
        Consumer<String> handler = deathHandler;
        log.warn("[TRANSPORT-DEAD] port={}, reason={}", port.serialInfo.portName, reason);
        if (handler != null) {
            handler.accept(reason);
        }
    }

    @Override
    public void onTransportDead(Consumer<String> handler) {
        this.deathHandler = handler;
    }

    @Override
    public int drainRx() {
        SerialPort serialPort = port.serialPort;
        int drained = 0;
        int rounds = 0;
        while (serialPort != null && serialPort.isOpen() && rounds++ < MAX_RX_DRAIN_ROUNDS) {
            int available = serialPort.bytesAvailable();
            if (available <= 0) {
                return drained;
            }
            byte[] sink = new byte[available];
            serialPort.readBytes(sink, available);
            drained += available;
        }
        if (serialPort != null && serialPort.bytesAvailable() > 0) {
            // 有界 drain 后内核仍有数据=对端持续回灌（如故障设备刷屏），如实上报不无限循环
            log.warn("[RX-DRAIN] port={}, {} 轮 drain 后内核仍有 {} B，疑似对端持续回灌",
                    port.serialInfo.portName, MAX_RX_DRAIN_ROUNDS, serialPort.bytesAvailable());
        }
        return drained;
    }

    @Override
    public String describe() {
        return "uart:" + port.serialInfo.portName;
    }

    /**
     * 推模型 RX 读循环：SEMI_BLOCKING 读阻塞于内核 poll，数据到达即投 sink，读超时
     * （n=0）继续等待，读死亡（n&lt;0/异常）上报死亡通知后退出；{@link #stopReading()}
     * 置停后线程在当前阻塞读返回时自退，此后到达字节不再投递（契约 2）。closePort 使
     * 阻塞读立即返 -1（契约 3，看门狗跨线程强拆依赖）。
     */
    private final class RxLoop implements Runnable {

        private final ReadSink sink;
        private final String threadName;
        private volatile boolean stopRequested;
        private volatile Thread thread;

        RxLoop(ReadSink sink, String portName) {
            this.sink = sink;
            this.threadName = "serial-rx-" + portName;
        }

        boolean alive() {
            Thread t = thread;
            return t != null && t.isAlive();
        }

        void start() {
            Thread created = new NamedThreadFactory(threadName, true).newThread(this);
            thread = created;
            created.start();
        }

        void stop() {
            stopRequested = true;
        }

        @Override
        public void run() {
            while (!stopRequested) {
                // 每次读独立分配：事件入队后交付所有权，复用缓冲会被下一轮读覆盖
                byte[] chunk = new byte[RX_READ_CHUNK];
                int n;
                try {
                    n = read(chunk, chunk.length);
                } catch (RuntimeException readCrashed) {
                    // 传输层读异常（如 USB 拔出抛错形态）：与返 -1 同收敛——死亡通知恰一次后退出
                    notifyTransportDead("read-exception: " + readCrashed.getMessage());
                    return;
                }
                if (stopRequested) {
                    // 停止请求先于投递：阻塞读返回后先查停，本批字节不再投递（契约 2）
                    log.info("[RX-STOP] port={}, 读线程自退（stopReading，本批 {} B 未投递）",
                            port.serialInfo.portName, Math.max(n, 0));
                    return;
                }
                if (n > 0) {
                    sink.onBytes(chunk, n);
                } else if (n < 0) {
                    // -1 哨兵：口已关（closePort / 对端消失）。死亡通知后退出，重开重新 start。
                    notifyTransportDead("read-closed(-1)");
                    return;
                }
                // n == 0：读超时醒（无数据），继续下一轮阻塞等待
            }
            log.info("[RX-STOP] port={}, 读线程自退（stopReading）", port.serialInfo.portName);
        }
    }
}
