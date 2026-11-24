package com.ecat.integration.SerialIntegration;

import java.util.function.Consumer;

/**
 * 端口传输缝（推模型）：执行者与物理传输的唯一交界。执行者只依赖本接口，不感知
 * 底层是本地 COM（jSerialComm）还是串口服务器 TCP（P4 的 TcpTransport）——同一条
 * 执行者状态机驱动两种传输。
 *
 * <p><b>缝的线程契约</b>（实现必须遵守，执行者据此保证无锁串行）：
 * <ol>
 *   <li>{@code sink.onBytes} 在<b>传输读线程</b>上调用；实现只做事件投递（如
 *       {@code executor.submitBytes}），必须非阻塞、无锁、不解析。</li>
 *   <li>{@code stopReading()} 之后到达的字节不再投递给 sink（不进执行者）——DRAINING
 *       排空规则的传输层落点（COM 实现下迟到字节滞留内核缓冲，由 {@link #drainRx()}
 *       排空；TCP 实现下在回调层直接丢弃）。</li>
 *   <li>{@code close()} <b>任意线程安全</b>（看门狗跨线程强拆依赖），且使阻塞中的读
 *       立即返回（COM 实现经 closePort 唤醒内核 poll，读返 -1）。</li>
 *   <li>{@code write} 返回 -1 ⇒ 传输已死 ⇒ 死亡通知必至且恰一次（允许 -1 与通知竞速，
 *       接收方以先到者为准、幂等处理）。死亡通知回调可能在写调用线程上同步触发，
 *       回调体必须非阻塞。</li>
 *   <li>{@code startReading} 幂等（重复调用无副作用）；{@code stopReading} 对未 start
 *       的调用是 no-op。</li>
 * </ol>
 *
 * <p>包私有：SDK 内部缝，不对外暴露；消费方一律经 SerialSource API。
 *
 * @author coffee
 */
interface PortTransport {

    /**
     * 物理打开传输（口执行线程调用）。实现负责完整物理参数设置与打开失败记账；
     * 打开成功前后的接收清洗由调用方经 {@link #drainRx()} 编排。
     *
     * @return true=物理通道已打开；false=打开失败（口不存在/被拒/参数无效，端口保持关闭）
     */
    boolean open();

    /**
     * 物理关闭传输（口执行线程与看门狗跨线程调用）。幂等；已关闭再关是 no-op。
     *
     * @return true=本次调用实际执行了关闭；false=本就未打开
     */
    boolean close();

    /** 传输通道是否处于打开状态（任意线程可读）。 */
    boolean isOpen();

    /**
     * 同步写（口执行线程调用）：把 buffer 前 length 字节写入物理通道。
     *
     * @return 已写出字节数；-1 = 本次未写出且传输已死（死亡通知必至，契约 4）
     */
    int write(byte[] buffer, int length);

    /**
     * 直读一次物理通道（测试模式 mock 口直读路径专用；生产推模型不经此方法）。
     *
     * @return 实际读到的字节数（0=无数据；-1=传输已死）
     */
    int read(byte[] buffer, int length);

    /**
     * 当前可读字节数（直读路径定缓冲大小用；哨兵语义与底层库一致：-1=传输已关）。
     *
     * @return 可读字节数；-1 = 传输已关
     */
    int available();

    /**
     * 开始向 sink 投递到达字节（推模型 RX）：实现自持读线程/回调注册，数据到达即调
     * {@code sink.onBytes(buf, len)}。幂等（契约 5）。
     */
    void startReading(ReadSink sink);

    /**
     * 停止向 sink 投递（契约 2/5）：对未 start 的调用是 no-op；调用后传输不再把字节
     * 交给 sink。COM 实现语义：读线程在下次读返回后自退，此后字节滞留内核缓冲，
     * 由 {@link #drainRx()} 显式排空或由恢复后的重新读取消费。
     */
    void stopReading();

    /**
     * 排空物理层已到达未投递的字节（有界循环读空）。重开后 RX 清洗与排空规则共用。
     *
     * @return 排空丢弃的字节总数
     */
    int drainRx();

    /**
     * 注册传输死亡通知接收端（契约 4 的通知通道）：拔 USB / 写反压 / 读异常 / 断线
     * 终态等「通道已死」时刻，传输以 reason 回调一次。须在 {@link #open()} 前注册；
     * 回调可能在任意线程触发，回调体必须非阻塞（如投递执行者事件即返）。
     *
     * @param handler 死亡通知接收端（reason 文本用于日志定位）
     */
    void onTransportDead(Consumer<String> handler);

    /** 诊断名（日志定位用，如 {@code uart:ttyUSB0} / {@code tcp:10.0.0.1:4001}）。 */
    String describe();
}
