package com.ecat.integration.SerialIntegration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * F-43 清洗②（bugs/bug-record-20260826-093000 Q2「残留应答污染」）的 TDD 单测。
 *
 * <p>问题形态：{@code recoverWedgedPort}/{@code openPort} 重开路径不清 RX——强拆前在途的
 * 应答尾巴（内核 tty 队列 + 应用层 continuousReceiveBuffer）在重开后落进新事务的缓冲，
 * 经 {@code deliverBufferedData} 注册即回放投给新命令监听器 → 误配对（F-42 Q2 形态）。
 *
 * <p>修复契约：
 * <ol>
 *   <li>端口（重）开后 drain 内核 RX（有界循环读空）+ 清应用层缓冲——重开后首读零旧字节；</li>
 *   <li>{@code deliverBufferedData} 回放加时间窗：距上次发送超过窗（读超时 × 2）的旧字节
 *       丢弃不投递——堵迟到多行应答把旧字节整帧误配对给新命令（F-30 乱序段形态）。</li>
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
     * 【RED：内核残留】drain 必须读空内核 RX 队列：bytesAvailable 持续有旧字节时，
     * drainAndClearReceiveBuffer 有界循环读空（readBytes 被调）且应用层缓冲同时被清。
     */
    @Test
    public void drainClearsKernelAndAppBuffers() {
        // 内核残留 2 轮旧字节后被读空
        AtomicInteger avail = new AtomicInteger(4);
        when(serialPort.bytesAvailable()).thenAnswer(inv -> avail.get());
        when(serialPort.readBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            avail.addAndGet(-b.length);
            return b.length;
        });
        // 应用层残留：强拆前在途应答尾巴已进 continuousReceiveBuffer
        port.handleIncomingData(new byte[]{'S', 'T', 'A', 'L', 'E'}, 5);

        port.drainAndClearReceiveBuffer("test-drain");

        verify(serialPort, times(1)).readBytes(any(byte[].class), anyLong());
        // 应用层缓冲同步被清：注册监听器回放不投递任何旧字节
        CountingListener delivered = new CountingListener();
        port.deliverBufferedData(delivered);
        org.junit.Assert.assertEquals("drain 后应用层缓冲应为空", 0, delivered.count.get());
    }

    /**
     * 【RED：迟到旧字节误配对】距上次发送超过回放窗（读超时×2）的缓冲字节，
     * 监听器注册时必须丢弃不投递（现状：注册即回放 → 旧帧整体误配对新命令）。
     */
    @Test
    public void deliverBufferedData_dropsStaleBytesBeyondReplayWindow() {
        // 模拟「上次发送发生在很久之前」：残留字节是迟到旧应答
        port.setLastSendTimeForTest(System.currentTimeMillis() - 10_000L);
        port.handleIncomingData(new byte[]{'$', 'O', 'L', 'D', '\r', '\n'}, 6);

        CountingListener listener = new CountingListener();
        port.deliverBufferedData(listener);

        org.junit.Assert.assertEquals("超过回放窗的旧字节必须丢弃", 0, listener.count.get());
        // 丢弃同时清缓冲（避免下次注册再投）
        CountingListener second = new CountingListener();
        port.deliverBufferedData(second);
        org.junit.Assert.assertEquals("丢弃后缓冲应已清空", 0, second.count.get());
    }

    /** 窗口内（刚发送后到达的应答）回放语义保留：正常投递给新监听器。 */
    @Test
    public void deliverBufferedData_replaysFreshBytesWithinWindow() {
        port.setLastSendTimeForTest(System.currentTimeMillis());
        port.handleIncomingData(new byte[]{'O', 'K'}, 2);

        CountingListener delivered = new CountingListener();
        port.deliverBufferedData(delivered);
        org.junit.Assert.assertEquals("窗口内字节应正常回放", 1, delivered.count.get());
    }
}
