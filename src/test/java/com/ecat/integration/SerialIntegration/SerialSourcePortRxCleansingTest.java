package com.ecat.integration.SerialIntegration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * F-43 清洗②（bugs/bug-record-20260826-093000 Q2「残留应答污染」）的回归单测
 * （serial IO 最小模型语义更新版）。
 *
 * <p>问题形态：重开路径不清 RX——强拆前在途的应答尾巴（内核 tty 队列 + 应用层接收缓冲）
 * 在重开后落进新对话的缓冲，经监听器注册回放投给新命令监听器 → 误配对。
 *
 * <p>修复契约（现行）：
 * <ol>
 *   <li>端口（重）开后 drain 内核 RX（有界循环读空）+ 清应用层缓冲——重开后首读零旧字节；</li>
 *   <li>RX/回放都经口执行线程事件串行（缓冲归口执行线程私有）：到达字节先入缓冲，
 *       监听器注册时的回放投递「注册时刻仍在缓冲的未消费字节」恰好一次；</li>
 *   <li>旧「2×超时回放窗」退役：异常结束后的迟到旧字节由排空（DRAINING 丢弃 + 清缓冲）
 *       结构性根除，串行使「缓冲内容只属于当前对话」成为结构事实。</li>
 * </ol>
 *
 * @author coffee
 */
public class SerialSourcePortRxCleansingTest {

    private SerialSourcePort port;
    private SerialPort serialPort;

    /** 计数捕获监听器（SerialDataListener 非函数式接口：含 onError，须具名实现）。 */
    private static final class CountingListener implements com.ecat.integration.SerialIntegration.Listener.SerialDataListener {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onDataReceived(byte[] data, int length) {
            count.incrementAndGet();
        }

        @Override
        public void onError(Exception ex) {
        }
    }

    @Before
    public void setUp() {
        serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        when(serialPort.bytesAvailable()).thenReturn(0);
        when(serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return b.length;
        });
        port = new SerialSourcePort(new SerialInfo("/dev/ttyTEST1", 9600, 8, 1, 0, 0, 500), 5, null);
        port.serialPort = serialPort;
    }

    /**
     * 【内核残留】drain 必须读空内核 RX 队列：bytesAvailable 持续有旧字节时，
     * drainAndClearReceiveBuffer 有界循环读空（readBytes 被调）且应用层缓冲同时被清
     * ——drain 后监听器注册回放不投递任何旧字节。
     */
    @Test
    public void drainClearsKernelAndAppBuffers() throws Exception {
        // 内核残留旧字节后被读空
        AtomicInteger avail = new AtomicInteger(4);
        when(serialPort.bytesAvailable()).thenAnswer(inv -> avail.get());
        when(serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            avail.addAndGet(-b.length);
            return b.length;
        });
        // 应用层残留：强拆前在途应答尾巴已进接收缓冲（事件驱动到达）
        port.handleIncomingData(new byte[]{'S', 'T', 'A', 'L', 'E'}, 5);
        port.executor().awaitEventLoopForTest();

        port.drainAndClearReceiveBuffer("test-drain");

        verify(serialPort, times(1)).readBytes(any(byte[].class), anyLong());
        // 应用层缓冲已被清：注册监听器回放不投递任何旧字节
        CountingListener delivered = new CountingListener();
        port.deliverBufferedData(delivered);
        port.executor().awaitEventLoopForTest();
        org.junit.Assert.assertEquals("drain 后应用层缓冲应为空", 0, delivered.count.get());
    }

    /**
     * 【回放语义】监听器注册时已在缓冲的未消费字节恰好回放一次（发送与监听器注册间的
     * 到达竞态由回放兜住）；回放即消费（readAndClear），二次注册零重复投递。
     */
    @Test
    public void bufferedBytesAreReplayedToNextListenerExactlyOnce() throws Exception {
        port.handleIncomingData(new byte[]{'O', 'K'}, 2);
        port.executor().awaitEventLoopForTest();

        CountingListener delivered = new CountingListener();
        port.deliverBufferedData(delivered);
        port.executor().awaitEventLoopForTest();
        org.junit.Assert.assertEquals("已在缓冲的字节应回放给新监听器", 1, delivered.count.get());

        // 回放即消费：缓冲已清，后续注册零重复
        CountingListener second = new CountingListener();
        port.deliverBufferedData(second);
        port.executor().awaitEventLoopForTest();
        org.junit.Assert.assertEquals("回放后缓冲应已清空（零重复投递）", 0, second.count.get());
    }
}
