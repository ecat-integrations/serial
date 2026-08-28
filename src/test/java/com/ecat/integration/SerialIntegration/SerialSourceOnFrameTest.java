package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.fazecast.jSerialComm.SerialPort;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SerialSource#onFrame(Consumer)} 被动接收统一注册形态（17 号 v2.1 §2.2
 * 薄包装契约）：适配到既有 {@code SerialDataListener} 体系（注册/注销/全量清理原样继承），
 * 消费方收到恰好 length 字节的切片。真实 {@code SerialSource} 实例 + mock 串口注入
 * （{@code SerialIntegrationReconfigureSettingsTest} 同款：绕过 register→openPort→
 * getCommPort 对假口名抛异常），经包内可见的 {@code notifyListeners} 直驱数据面。
 *
 * @author coffee
 */
public class SerialSourceOnFrameTest {

    private SerialSource source;
    private List<byte[]> received;

    @Before
    public void setUp() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("ONFRAME-TEST-PORT", 9600, 8, 1, 0), 1, null);
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.isOpen()).thenReturn(true);
        port.serialPort = serialPort;   // 已开短路：构造路径零 jSerialComm 副作用
        source = new SerialSource(port, "onframe-test");
        received = new CopyOnWriteArrayList<>();
    }

    /** 注册即收数：消费方收到恰好 length 字节（短于底层数组时切片拷贝）。 */
    @Test
    public void onFrameDeliversExactLengthSlice() {
        source.onFrame(received::add);

        byte[] full = new byte[] {1, 2, 3, 4, 5};
        source.notifyListeners(full, full.length);
        byte[] partial = new byte[] {6, 7, 8, 9, 10};
        source.notifyListeners(partial, 3);

        assertEquals("两帧都必须送达", 2, received.size());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, received.get(0));
        assertArrayEquals("部分长度帧须切片为恰好 length 字节", new byte[] {6, 7, 8}, received.get(1));
        assertSame("等长帧零拷贝直传（读路径每读新分配，无复用竞争）",
                full, received.get(0));
    }

    /** 返回适配器可注销：removeDataListener 后不再送达。 */
    @Test
    public void onFrameAdapterIsRemovableViaExistingListenerApi() {
        SerialDataListener adapter = source.onFrame(received::add);
        assertNotNull("返回适配器供注销（既有 listener 体系词汇）", adapter);
        assertEquals(1, source.getDataListenerCount());

        source.notifyListeners(new byte[] {1}, 1);
        assertEquals(1, received.size());

        source.removeDataListener(adapter);
        assertEquals(0, source.getDataListenerCount());
        source.notifyListeners(new byte[] {2}, 1);
        assertEquals("注销后不得再送达", 1, received.size());
    }

    /** 既有全量清理覆盖 onFrame 注册（closePort 同款收尾路径）。 */
    @Test
    public void removeAllDataListenersCoversOnFrameRegistration() {
        source.onFrame(received::add);
        source.removeAllDataListeners();
        assertEquals("全量清理后监听器计数归零", 0, source.getDataListenerCount());

        source.notifyListeners(new byte[] {1}, 1);
        assertTrue("清理后不得再送达", received.isEmpty());
    }

    /** 消费方抛异常不打断管线：既有 notifyListeners 兜住转 onError（包装记 warn），后续帧继续送达。 */
    @Test
    public void consumerThrowingDoesNotKillSubsequentDelivery() {
        final List<byte[]> good = received;
        source.onFrame(frame -> {
            if (frame[0] == 1) {
                throw new IllegalStateException("frame handler boom");
            }
            good.add(frame);
        });

        source.notifyListeners(new byte[] {1, 1}, 2);   // 抛异常帧
        source.notifyListeners(new byte[] {2, 2}, 2);   // 后续正常帧

        assertEquals("异常帧之后的帧必须继续送达（既有 onError 兜底语义继承）",
                1, received.size());
        assertArrayEquals(new byte[] {2, 2}, received.get(0));
    }

    /** null 消费方是编程错误：显式拒绝。 */
    @Test(expected = IllegalArgumentException.class)
    public void nullHandlerIsRejected() {
        source.onFrame(null);
    }
}
