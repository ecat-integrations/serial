package com.ecat.integration.SerialIntegration;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.TcpIntegration.config.TcpConfig;
import com.ecat.integration.TcpIntegration.sdk.TcpConnection;
import com.ecat.integration.TcpIntegration.sdk.TcpConnectionEvent;
import com.ecat.integration.TcpIntegration.sdk.TcpFrameHandler;

/**
 * {@link PortTransport} 的串口服务器 TCP 实现（RTU over TCP，tcp 4.0.0 连接层）：ECAT 作
 * TCP 客户端连硬件串口服务器，serial 协议一次编写两种传输——执行者状态机不经修改驱动
 * COM 与 TCP 两条物理通道。连接面 = {@link TcpConnection} 原始字节通道（未设 parser，
 * start 自动挂 RawChunkParser，入站字节走 onData 逃生口，逐字节块直投 sink）。
 *
 * <p><b>重连分工（§4.4）</b>：tcp 连接层的断线自动重连在本传输内被抑制——
 * {@code maxReconnectAttempts(0)} 使 ReconnectTcpClient 断线路径跳过 scheduleAutoReconnect
 * （其断开处有 {@code maxReconnectAttempts > 0} 门），重连唯一驱动方 = 执行者 RECOVERING：
 * 断线 → onDisconnected → {@link #onTransportDead}（"disconnected"）→ 执行者排空后
 * close + open（open 每次建全新 TcpConnection，release 幂等）——两套重连状态机互不覆盖。
 * 首连结果在 {@link #open()} 内有界等待；失败即 release（终结 TcpConnection.start 的
 * 首连后台重试链，不留孤儿重试循环）后如实返 false。
 *
 * <p><b>onData 回调线程契约（ecat-tcp-sched-N 车道）</b>：回调体只做 volatile 读 +
 * 投递（sink.onBytes → 执行者 submitBytes 非阻塞入队）或丢弃，零 IO 零锁零阻塞——
 * 车道回调内阻塞会拖垮全局 3 线程共享池（契约测试钉：慢消费者下回调管线仍逐块吞吐）。
 *
 * <p><b>投递门（stopReading 语义）</b>：连接建立即挂 onData（连接即收），startReading
 * 仅开投递门——open 与 startReading 之间到达的字节在回调层丢弃（与 COM 的
 * drainAndClearReceiveBuffer 窗口同一 RX 清洗语义）；stopReading 后到达字节在回调层
 * 丢弃不进执行者（缝契约 2 的 TCP 落点：TCP 无内核缓冲可滞留，丢弃即排空）。
 *
 * <p><b>拉读原语</b>：TCP 无 bytes-available/单次拉读原语（RX 仅推模型），
 * {@link #read}/{@link #available} 抛 UnsupportedOperationException 快速失败——
 * 唯一调用方是测试模式 mock 口直读路径，COM 档专属形态。
 *
 * <p><b>死亡形态收敛（恰一次）</b>：写失败/断线（onDisconnected）统一经
 * {@link #onTransportDead(Consumer)}；-1 与通知允许竞速，接收方幂等（缝契约 4）。
 * release 不发 onDisconnected（tcp 连接层语义），故 close 收场不触发死亡通知；
 * 旧连接的迟到 DISCONNECTED 事件按 connectionId 守卫丢弃（每次 open 换新 id）。
 *
 * @author coffee
 */
final class TcpTransport implements PortTransport {

    private static final Log log = LogFactory.getLogger(TcpTransport.class);

    /**
     * TCP 连接建立上界（毫秒）：open 在口执行线程同步等首连结果，本值约束不可达主机
     * （SYN 静默丢）时 open 的阻塞时长。量级论证：局域网串口服务器正常连接毫秒级；
     * open 阻塞的最坏容忍面 = 执行者 session register 的 10s ack 预算
     * （SerialPortExecutor.awaitEventAck），连接超时 5s + 等待余量 2s 共 7s 预留于其内。
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** open 内等待首连 CF 的余量（叠加于 {@link #CONNECT_TIMEOUT_MS} 之上，让 CF 自身先判终态）。 */
    private static final int CONNECT_AWAIT_MARGIN_MS = 2_000;

    private final String host;
    private final int port;
    /** 诊断名（注册表键 = portName，TCP 档为 host:port 形态）。 */
    private final String diagnosticName;

    /** 端口级连接归属：端口只持字符串身份（portName），按仓内 LEGACY 显式字符串惯例登记。 */
    private final ResourceOwner owner;

    /** 活动连接；null = 未打开/已关闭。open/close 只在口执行线程，isOpen 任意线程读（volatile）。 */
    private volatile TcpConnection connection;

    /** 活动连接 id（迟到事件守卫：每次 open 换新连接，旧连接的迟到断开事件按 id 丢弃）。 */
    private volatile String activeConnectionId;

    /** 死亡通知接收端（open 前经 {@link #onTransportDead(Consumer)} 注册）。 */
    private volatile Consumer<String> deathHandler;

    /** 死亡通知「恰一次」守卫：每次成功打开重置（一场生死周期恰一次通知）。 */
    private final AtomicBoolean deadNotified = new AtomicBoolean();

    /** 投递门：true = 到达字节投 sink；false = 回调层丢弃（stopReading / 未 startReading）。 */
    private volatile boolean delivering;

    /** RX 投递端（startReading 注入；投递门关时仍被 onData 触碰前短路）。 */
    private volatile ReadSink sink;

    TcpTransport(String host, int port, String diagnosticName) {
        this.host = host;
        this.port = port;
        this.diagnosticName = diagnosticName;
        this.owner = ResourceOwner.legacy(diagnosticName);
    }

    @Override
    public boolean open() {
        // 重连 = 全新连接：上一场连接对象（含其首连重试链）必须先释放再建新（防泄漏重试循环）
        TcpConnection previous = connection;
        if (previous != null) {
            previous.release();
            connection = null;
        }
        TcpConnection created = TcpConnection.withOwner(owner)
                .connect(host, port, tcpConfig());
        created.onFrame(frameHandler);
        CompletableFuture<Boolean> firstConnect = created.start();
        boolean opened;
        try {
            opened = firstConnect.get(CONNECT_TIMEOUT_MS + CONNECT_AWAIT_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            opened = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}——首连 {}ms 未判终态（连接已释放，重试链已终结）",
                    diagnosticName, host, port, CONNECT_TIMEOUT_MS + CONNECT_AWAIT_MARGIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            opened = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}——open 等待被中断", diagnosticName, host, port);
        } catch (ExecutionException e) {
            opened = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}, cause={}",
                    diagnosticName, host, port, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
        if (!opened) {
            // 失败收场必须 release：终结 TcpConnection.start 的首连后台重试链（该链不受
            // maxReconnectAttempts 管，唯一取消点 = release），不留打向串口服务器的孤儿重试循环
            created.release();
            return false;
        }
        connection = created;
        activeConnectionId = created.connectionId();
        deadNotified.set(false);
        log.info("[OPENED] tcp={}, host={}, port={}, connId={}",
                diagnosticName, host, port, activeConnectionId);
        return true;
    }

    /**
     * TCP 连接参数（§4.4 抑制点）：{@code maxReconnectAttempts(0)} 抑制断线后台自动重连
     * （ReconnectTcpClient 断开处的 {@code maxReconnectAttempts > 0} 门），重连唯一驱动方
     * = 执行者 RECOVERING；connectionTimeout 定 open 阻塞上界（类注释量级论证）。
     */
    private TcpConfig tcpConfig() {
        return TcpConfig.builder()
                .host(host)
                .port(port)
                .maxReconnectAttempts(0)
                .connectionTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @Override
    public boolean close() {
        TcpConnection current = connection;
        if (current == null) {
            return false;
        }
        connection = null;
        activeConnectionId = null;
        boolean wasOpen = current.isConnected();
        // release 幂等且不发 onDisconnected（tcp 连接层语义）——close 收场不触发死亡通知；
        // 在途写请求随 release 异常完成（写线程从阻塞等待中得释返 -1，看门狗强拆依赖）
        current.release();
        delivering = false;
        log.info("[CLOSED] tcp={}, fd released (wasOpen={})", diagnosticName, wasOpen);
        return wasOpen;
    }

    @Override
    public boolean isOpen() {
        TcpConnection current = connection;
        return current != null && current.isConnected();
    }

    @Override
    public int write(byte[] buffer, int length) {
        TcpConnection current = connection;
        if (current == null || !current.isConnected()) {
            // 断连写：不触 send（其断线路径含一次内联单发重连，会绕开执行者 RECOVERING
            // 分工）——直接 -1 + 死亡通知，恢复唯一走执行者 close+open
            notifyTransportDead("write-disconnected");
            return -1;
        }
        // 缝契约：写 buffer 前 length 字节；send 无 length 参数，非全长时截取
        // （执行者写路径恒传全长缓冲，本分支不 hotter；契约对任意调用方成立）
        byte[] payload = length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
        try {
            // 真完成语义：CF 在 selector 线程全部写出 socket 时完成（阻塞等待与 COM 阻塞写
            // 同模型）；断开/释放 failPendingWrites 异常完成——挂死写由看门狗 close 强拆得释
            return current.send(payload).join();
        } catch (RuntimeException sendFailed) {
            notifyTransportDead("write-failed: " + sendFailed.getMessage());
            return -1;
        }
    }

    @Override
    public int read(byte[] buffer, int length) {
        throw new UnsupportedOperationException("TCP 传输无拉读原语（RX 仅推模型 onData；"
                + "直读路径是 COM mock 口专属形态）");
    }

    @Override
    public int available() {
        throw new UnsupportedOperationException("TCP 传输无 bytes-available 原语（RX 仅推模型 onData）");
    }

    // ==================== 推模型 RX（onData 投递门） ====================

    @Override
    public void startReading(ReadSink sink) {
        this.sink = sink;          // 先落 sink 再开门（volatile 写序：开门必见非空 sink）
        this.delivering = true;    // 幂等（缝契约 5）：重复 start 无副作用
    }

    @Override
    public void stopReading() {
        this.delivering = false;   // 对未 start 的调用是 no-op（门本就关；缝契约 5）
    }

    /**
     * TCP 档排空 = no-op 返 0：TCP 无内核缓冲排空原语；「已到达未投递」字节不存在积压形态
     * ——投递门关时字节在 onData 回调层即时丢弃（丢弃即排空），open→startReading 窗口的
     * RX 清洗同由该门承接（COM 档同窗靠本方法排内核缓冲，两档语义对齐、机制各宜）。
     */
    @Override
    public int drainRx() {
        return 0;
    }

    /** 死亡通知（恰一次守卫）：写死亡/断线统一收敛，接收方幂等处理（缝契约 4）。 */
    private void notifyTransportDead(String reason) {
        if (!deadNotified.compareAndSet(false, true)) {
            return;
        }
        Consumer<String> handler = deathHandler;
        log.warn("[TRANSPORT-DEAD] tcp={}, host={}, port={}, reason={}",
                diagnosticName, host, port, reason);
        if (handler != null) {
            handler.accept(reason);
        }
    }

    @Override
    public void onTransportDead(Consumer<String> handler) {
        this.deathHandler = handler;
    }

    @Override
    public String describe() {
        return "tcp:" + host + ":" + port;
    }

    /**
     * 单一回调面（原始字节 + 生命周期共用一注册）：onData 只做 volatile 读 + 投递/丢弃
     * （零阻塞，ecat-tcp-sched 车道契约）；DISCONNECTED 经 connectionId 守卫后上报死亡
     * ——重连由执行者 RECOVERING 驱动，本传输不自行重连（§4.4）。
     */
    private final TcpFrameHandler frameHandler = new TcpFrameHandler() {
        @Override
        public void onFrame(TcpConnectionEvent event) {
            // 结构性不可达：本传输不设 parser（start 恒挂 RawChunkParser 走 onData 字节级
            // 通道）——到达即类不变式被破坏，快速失败优于静默丢帧
            throw new IllegalStateException("TcpTransport 恒为字节级模式（无 parser），onFrame 不应被触发");
        }

        @Override
        public void onData(TcpConnectionEvent event) {
            byte[] chunk = event.getFrame();
            if (chunk == null || chunk.length == 0) {
                return;
            }
            if (!delivering) {
                return; // 投递门关：回调层丢弃（RX 清洗窗 / stopReading 后迟到字节）
            }
            ReadSink current = sink;
            if (current == null) {
                return;
            }
            current.onBytes(chunk, chunk.length);
        }

        @Override
        public void onTransportStateChanged(TcpConnectionEvent event) {
            if (event.getState() != TcpConnectionEvent.TransportState.DISCONNECTED) {
                return;
            }
            String currentId = activeConnectionId;
            if (currentId == null || !currentId.equals(event.getConnId())) {
                // 旧连接（已 close/release）的迟到断开事件：丢弃，防误杀新一场连接
                return;
            }
            notifyTransportDead("disconnected");
        }
    };
}
