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
 * <p><b>重连分工（§3.4：连接重连归 tcp 层，对话层排空归执行者）</b>：连接参数整块来自
 * {@link SerialCommSettings#parse} 携带的 {@link TcpConfig}（maxReconnectAttempts/
 * reconnectTimeout 与 tcp schema 缺省同源），断线后台自动重连由 tcp 层
 * {@code ReconnectTcpClient} 自有状态机驱动（退避/重试计数/永不放弃），本传输不复制重连
 * 逻辑。事件对接：断线（DISCONNECTED）→ 投递门关闭（重连期间一切字节丢弃——迟到字节
 * 不进新对话）+ 链路断开通知（执行者排空在飞对话）；重连成功（RECONNECT_SUCCEEDED）→
 * 投递门恢复。tcp 层自动重连永不放弃（失败后无限再调度），故不存在「tcp 放弃」终死形态
 * ——链路断开通知语义 = 「每次断线恰一次」而非终死；显式 {@link #close()} 释放连接
 * （终结重连链）不发断开通知（tcp 连接层语义）。首连结果在 {@link #open()} 内有界等待；
 * 失败即 release（终结 TcpConnection.start 的首连后台重试链，不留孤儿重试循环）后如实
 * 返 false。{@link #isOpen()} 是管理面语义（open 过且未 close）：断线重连期间仍为 open
 * ——执行者写前自动重开不与 tcp 层重连抢连接（两套状态机并行会互毁），重连成功后的
 * 下一场对话自然恢复。
 *
 * <p><b>onData 回调线程契约（ecat-tcp-sched-N 车道）</b>：回调体只做 volatile 读 +
 * 投递（sink.onBytes → 执行者 submitBytes 非阻塞入队）或丢弃，零 IO 零锁零阻塞——
 * 车道回调内阻塞会拖垮全局 3 线程共享池（契约测试钉：慢消费者下回调管线仍逐块吞吐）。
 *
 * <p><b>投递门（stopReading 语义 + 链路门）</b>：两道门相与——用户门 {@code delivering}
 * 由 startReading/stopReading 控制（open 与 startReading 之间到达的字节在回调层丢弃，
 * 与 COM 的 drainAndClearReceiveBuffer 窗口同一 RX 清洗语义；stopReading 后到达字节
 * 丢弃不进执行者，缝契约 2 的 TCP 落点）；链路门 {@code linkUp} 断线自动关、重连成功
 * 自动开。字节投递与状态事件共用 tcp 连接层同一 FIFO 车道，断线事件处理（关门）在车道
 * 序上先于重连成功处理（开门）：断线前积压的迟到块要么在关门之前按当时门态处理（断线前
 * 正常流），要么关门后丢弃——恢复后的新对话不会吃到旧连接残字节。
 *
 * <p><b>拉读原语</b>：TCP 无 bytes-available/单次拉读原语（RX 仅推模型），
 * {@link #read}/{@link #available} 抛 UnsupportedOperationException 快速失败——
 * 唯一调用方是测试模式 mock 口直读路径，COM 档专属形态。
 *
 * <p><b>写路径与在飞对话</b>：断连写/写失败一律 -1（对话失败收场），不再触发终死通知
 * ——断线通知由 DISCONNECTED 事件唯一承载（写失败与断线同源：send 挂死写由 tcp 连接层
 * failPendingWrites 异常完成，disconnectInternal 随后发 DISCONNECTED）。写挂死兜底 =
 * 执行者看门狗跨线程 {@link #close()} 强拆（release 终结重连链）+ 写前自动重开重建。
 *
 * @author coffee
 */
final class TcpTransport implements PortTransport {

    private static final Log log = LogFactory.getLogger(TcpTransport.class);

    /**
     * TCP 连接建立上界（毫秒）：open 在口执行线程同步等首连结果，本值约束不可达主机
     * （SYN 静默丢）时 open 的阻塞时长，是「管理动作的响应性预算」（执行者 session
     * register 的 10s ack 预算内，SerialPortExecutor.awaitEventAck）。socket 层连接
     * 超时独立走 TcpConfig.connectionTimeout（cfg 面，可配），两者正交。
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** open 内等待首连 CF 的余量（叠加于 {@link #CONNECT_TIMEOUT_MS} 之上，让 CF 自身先判终态）。 */
    private static final int CONNECT_AWAIT_MARGIN_MS = 2_000;

    private final String host;
    private final int port;
    /** 诊断名（注册表键 = portName，TCP 档为 host:port 形态）。 */
    private final String diagnosticName;

    /** 连接参数整块（host/port/重连/超时族）——TcpConnection 构造的唯一参数源。 */
    private final TcpConfig tcpConfig;

    /** 端口级连接归属：端口只持字符串身份（portName），按仓内 LEGACY 显式字符串惯例登记。 */
    private final ResourceOwner owner;

    /** 活动连接；null = 未打开/已关闭。open/close 只在口执行线程，isOpen 任意线程读（volatile）。 */
    private volatile TcpConnection connection;

    /** 活动连接 id（迟到事件守卫：每次 open 换新连接，旧连接的迟到断开事件按 id 丢弃）。 */
    private volatile String activeConnectionId;

    /** 管理面 open 态（open 成功置位、close 撤销）：断线重连期间保持 true——isOpen 不随数据面抖动。 */
    private volatile boolean opened;

    /** 链路断开通知接收端（open 前经 {@link #onTransportDead(Consumer)} 注册）。 */
    private volatile Consumer<String> deathHandler;

    /** 链路门：true = 允许投递；断线（DISCONNECTED）关、重连成功（RECONNECT_SUCCEEDED）开。 */
    private volatile boolean linkUp;

    /** 投递门：true = 到达字节投 sink；false = 回调层丢弃（stopReading / 未 startReading）。 */
    private volatile boolean delivering;

    /** RX 投递端（startReading 注入；任一门关时仍被 onData 触碰前短路）。 */
    private volatile ReadSink sink;

    TcpTransport(String host, int port, String diagnosticName, TcpConfig tcpConfig) {
        if (tcpConfig == null) {
            // 合法边界非兜底：TCP 档 SerialInfo 唯一产出口 = SerialCommSettings.parse
            // （TcpCommSettings.parse 恒产非 null TcpConfig）——null 即构造面 bug，快速失败
            throw new IllegalArgumentException("TcpConfig 不能为 null（TCP 档连接参数由 SerialCommSettings.parse 携带）");
        }
        this.host = host;
        this.port = port;
        this.diagnosticName = diagnosticName;
        this.tcpConfig = tcpConfig;
        this.owner = ResourceOwner.legacy(diagnosticName);
    }

    @Override
    public boolean open() {
        // 重连 = 全新连接：上一场连接对象（含其重连/重试链）必须先释放再建新（防泄漏重试循环）
        TcpConnection previous = connection;
        if (previous != null) {
            previous.release();
            connection = null;
        }
        TcpConnection created = TcpConnection.withOwner(owner)
                .connect(host, port, tcpConfig);
        created.onFrame(frameHandler);
        CompletableFuture<Boolean> firstConnect = created.start();
        boolean firstConnected;
        try {
            firstConnected = firstConnect.get(CONNECT_TIMEOUT_MS + CONNECT_AWAIT_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            firstConnected = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}——首连 {}ms 未判终态（连接已释放，重试链已终结）",
                    diagnosticName, host, port, CONNECT_TIMEOUT_MS + CONNECT_AWAIT_MARGIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            firstConnected = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}——open 等待被中断", diagnosticName, host, port);
        } catch (ExecutionException e) {
            firstConnected = false;
            log.error("[OPEN FAILED] tcp={}, host={}, port={}, cause={}",
                    diagnosticName, host, port, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
        if (!firstConnected) {
            // 失败收场必须 release：终结 TcpConnection.start 的首连后台重试链（该链不受
            // maxReconnectAttempts 管，唯一取消点 = release），不留打向串口服务器的孤儿重试循环
            created.release();
            return false;
        }
        connection = created;
        activeConnectionId = created.connectionId();
        opened = true;
        linkUp = true;
        log.info("[OPENED] tcp={}, host={}, port={}, connId={}, reconnect(attempts={},interval={}ms)",
                diagnosticName, host, port, activeConnectionId,
                tcpConfig.getMaxReconnectAttempts(), tcpConfig.getReconnectTimeout().toMillis());
        return true;
    }

    @Override
    public boolean close() {
        TcpConnection current = connection;
        if (current == null && !opened) {
            return false;
        }
        connection = null;
        activeConnectionId = null;
        opened = false;
        linkUp = false;
        boolean wasOpen = current != null && current.isConnected();
        // release 幂等且不发 DISCONNECTED（tcp 连接层语义）——close 收场不触发断开通知；
        // 在途写请求随 release 异常完成（写线程从阻塞等待中得释返 -1，看门狗强拆依赖）
        if (current != null) {
            current.release();
        }
        delivering = false;
        log.info("[CLOSED] tcp={}, fd released (wasOpen={})", diagnosticName, wasOpen);
        return true;
    }

    /**
     * 管理面 open 态：open 成功后恒 true（含断线重连期间——tcp 层自愈中，执行者写前
     * 自动重开不得抢建连接）；close 后 false。数据面连通性看 {@code write} 结果与断线通知。
     */
    @Override
    public boolean isOpen() {
        return opened;
    }

    @Override
    public int write(byte[] buffer, int length) {
        TcpConnection current = connection;
        if (current == null || !current.isConnected()) {
            // 断连/重连中写：-1 快速失败（对话失败收场），不触 send（其断线路径含一次内联
            // 单发重连，会与 tcp 层自动重连并行开链）——链路恢复由 tcp 层重连完成，断线
            // 排空由 DISCONNECTED 事件通知执行者，本路径不发通知
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
            // 写失败 = 断线正在发生（failPendingWrites 同源）：-1 收场，DISCONNECTED 事件
            // 必至（disconnectInternal 在 failPendingWrites 后发断开通知）承载执行者排空
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

    // ==================== 推模型 RX（onData 投递门 + 链路门） ====================

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
     * ——任一门关时字节在 onData 回调层即时丢弃（丢弃即排空），open→startReading 窗口的
     * RX 清洗同由该门承接（COM 档同窗靠本方法排内核缓冲，两档语义对齐、机制各宜）。
     */
    @Override
    public int drainRx() {
        return 0;
    }

    /**
     * 链路断开通知：每次断线（DISCONNECTED 事件，连接代际守卫后）恰一次。接收方
     * （SerialSourcePort → 执行者 submitAbort）幂等：无在飞对话时无事可做。tcp 层自动
     * 重连永不放弃，本通知不是终死——重连成功经投递门恢复收发。
     */
    private void notifyLinkDown(String reason) {
        Consumer<String> handler = deathHandler;
        log.warn("[LINK-DOWN] tcp={}, host={}, port={}, reason={}（tcp 层自动重连中，重连参数 attempts={},interval={}ms）",
                diagnosticName, host, port, reason,
                tcpConfig.getMaxReconnectAttempts(), tcpConfig.getReconnectTimeout().toMillis());
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
     * （零阻塞，ecat-tcp-sched 车道契约）；状态变化经连接代际守卫后驱动链路门与断开通知
     * （§3.4：连接重连归 tcp 层，本传输只做对话层反应）。
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
            if (!delivering || !linkUp) {
                return; // 投递门关（RX 清洗窗 / stopReading 后）或链路门关（断线重连期间迟到字节）：回调层丢弃
            }
            ReadSink current = sink;
            if (current == null) {
                return;
            }
            current.onBytes(chunk, chunk.length);
        }

        @Override
        public void onTransportStateChanged(TcpConnectionEvent event) {
            // 连接代际守卫：旧连接（已 close/release）的迟到事件按 id 丢弃，防误动新一场
            String currentId = activeConnectionId;
            if (currentId == null || !currentId.equals(event.getConnId())) {
                return;
            }
            switch (event.getState()) {
                case DISCONNECTED:
                    // 断线：关链路门（重连期间迟到字节不进新对话）+ 通知执行者排空在飞对话
                    //（非终死——tcp 层自动重连永不放弃，恢复见 RECONNECT_SUCCEEDED）
                    linkUp = false;
                    notifyLinkDown("disconnected");
                    break;
                case RECONNECT_SUCCEEDED:
                    // tcp 层重连成功：恢复投递（用户门 delivering 不动——stopReading 语义保持）
                    linkUp = true;
                    log.info("[LINK-UP] tcp={}, 重连成功，恢复收发", diagnosticName);
                    break;
                case RECONNECTING:
                case RECONNECT_FAILED:
                    // 重连节拍观测面（autoReconnectTick 每轮失败后无限再调度——RECONNECT_FAILED
                    // 不是终态）：debug 记账，避免重连风暴刷屏
                    log.debug("[LINK-EVENT] tcp={}, state={}", diagnosticName, event.getState());
                    break;
                case CONNECTED:
                default:
                    break;
            }
        }
    };
}
