package com.ecat.integration.SerialIntegration.Listener;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ResponseHandlingContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * PooledSerialDataListener 单元测试
 *
 * 重点测试：分片响应场景
 * - 验证监听器在收到不完整响应时不会被移除
 * - 验证监听器在收到完整响应时才会被移除
 * - 验证数据追加逻辑正确
 *
 * @author coffee
 */
public class PooledSerialDataListenerTest {

    private PooledSerialDataListener listener;

    @Mock
    private SerialSource mockSerialSource;

    @Mock
    private CompletableFuture<ResponseHandlingContext<?>> mockFuture;

    private ResponseHandlingContext<String> context;
    private int removeListenerCallCount;
    private AutoCloseable mockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        listener = new PooledSerialDataListener();
        context = new ResponseHandlingContext<>("test_context");
        removeListenerCallCount = 0;

        // Mock removeDataListener to track calls
        doAnswer(invocation -> {
            removeListenerCallCount++;
            return null;
        }).when(mockSerialSource).removeDataListener(any(PooledSerialDataListener.class));
    }

    @After
    public void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
        listener = null;
        context = null;
    }

    // ==================== 完整响应测试 ====================

    @Test
    public void testCompleteResponse_ShouldRemoveListener() {
        // Arrange: 响应以 "$\r\n" 结尾表示完整
        String incompleteData = "17444,PM2.5,145.0,0.0,2332.2,105.1,0.00,";
        String completeData = incompleteData + "27.8,34.7,0.00000,0,40.1,26.9,11.2,0.00,29.2,2816,17039.0,16750.0,0.0,0.0,0,02,0.00,0.00,$\r\n";

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act: 发送完整响应
        byte[] data = completeData.getBytes();
        listener.onDataReceived(data, data.length);

        // Assert:
        // 1. 响应被正确接收
        assertEquals("Buffer content should match", completeData,
            context.getReceiveBuffer().toString());

        // 2. Future 应该完成
        verify(mockFuture, times(1)).complete(context);

        // 3. 监听器应该被移除
        assertEquals("Listener should be removed after complete response",
            1, removeListenerCallCount);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    // ==================== 分片响应测试 ====================

    @Test
    public void testFragmentedResponse_FirstPacketIncomplete_ShouldKeepListener() {
        // Arrange: 第一包数据不完整（没有 "$\r\n" 结尾）
        String firstPacket = "17444,PM2.5,145.0,0.0,2332.2,105.1,0.00,";

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act: 发送第一包（不完整）
        byte[] data1 = firstPacket.getBytes();
        listener.onDataReceived(data1, data1.length);

        // Assert:
        // 1. 第一包数据被追加到 buffer
        assertEquals("First packet should be in buffer", firstPacket,
            context.getReceiveBuffer().toString());

        // 2. Future 不应该完成
        verify(mockFuture, never()).complete(context);

        // 3. 监听器不应该被移除（这是关键！）
        assertEquals("Listener should NOT be removed after incomplete packet",
            0, removeListenerCallCount);
        verify(mockSerialSource, never()).removeDataListener(listener);
    }

    @Test
    public void testFragmentedResponse_SecondPacketCompletes_ShouldRemoveListener() {
        // Arrange: 模拟分片场景
        String firstPacket = "17444,PM2.5,145.0,0.0,2332.2,105.1,0.00,";
        String secondPacket = "27.8,34.7,0.00000,0,40.1,26.9,11.2,0.00,29.2,2816,17039.0,16750.0,0.0,0.0,0,02,0.00,0.00,$\r\n";
        String completeResponse = firstPacket + secondPacket;

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act 1: 发送第一包（不完整）
        byte[] data1 = firstPacket.getBytes();
        listener.onDataReceived(data1, data1.length);

        // Assert 1: 监听器未被移除
        assertEquals("Listener should still be active after first packet",
            0, removeListenerCallCount);

        // Act 2: 发送第二包（补全响应）
        byte[] data2 = secondPacket.getBytes();
        listener.onDataReceived(data2, data2.length);

        // Assert 2:
        // 1. 完整响应被正确拼接
        assertEquals("Complete response should be in buffer", completeResponse,
            context.getReceiveBuffer().toString());

        // 2. Future 完成
        verify(mockFuture, times(1)).complete(context);

        // 3. 监听器被移除
        assertEquals("Listener should be removed after complete response",
            1, removeListenerCallCount);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    @Test
    public void testFragmentedResponse_ThreePackets() {
        // Arrange: 模拟三分片场景
        String packet1 = "DATA1,VALUE1,";
        String packet2 = "VALUE2,VALUE3,";
        String packet3 = "VALUE4$\r\n";  // 完整响应标记
        String completeResponse = packet1 + packet2 + packet3;

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act 1: 第一包
        listener.onDataReceived(packet1.getBytes(), packet1.length());
        assertEquals("After packet 1: listener should not be removed",
            0, removeListenerCallCount);

        // Act 2: 第二包
        listener.onDataReceived(packet2.getBytes(), packet2.length());
        assertEquals("After packet 2: listener should not be removed",
            0, removeListenerCallCount);

        // Act 3: 第三包（完成）
        listener.onDataReceived(packet3.getBytes(), packet3.length());

        // Assert:
        assertEquals("Complete response should match", completeResponse,
            context.getReceiveBuffer().toString());
        verify(mockFuture, times(1)).complete(context);
        assertEquals("Listener should be removed after completion",
            1, removeListenerCallCount);
    }

    // ==================== 边界条件测试 ====================

    @Test
    public void testEmptyResponse_ShouldKeepListener() {
        // Arrange: 空响应
        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act: 发送空数据
        byte[] emptyData = new byte[0];
        listener.onDataReceived(emptyData, 0);

        // Assert: 监听器不应该被移除
        verify(mockFuture, never()).complete(context);
        verify(mockSerialSource, never()).removeDataListener(listener);
    }

    @Test
    public void testJustCompleteMarker_ShouldRemoveListener() {
        // Arrange: 只发送结束标记
        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act: 只发送 "$\r\n"
        String completeMarker = "$\r\n";
        listener.onDataReceived(completeMarker.getBytes(), completeMarker.length());

        // Assert: 应该完成并移除监听器
        verify(mockFuture, times(1)).complete(context);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    @Test
    public void testIncompleteThenEmptyThenComplete() {
        // Arrange: 不完整 -> 空包 -> 完整
        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act 1: 不完整
        listener.onDataReceived("DATA,".getBytes(), 5);
        verify(mockSerialSource, never()).removeDataListener(listener);

        // Act 2: 空包
        listener.onDataReceived(new byte[0], 0);
        verify(mockSerialSource, never()).removeDataListener(listener);

        // Act 3: 完成标记
        listener.onDataReceived("$\r\n".getBytes(), 3);

        // Assert: 最终应该完成
        verify(mockFuture, times(1)).complete(context);
        verify(mockSerialSource, times(1)).removeDataListener(listener);
        assertEquals("DATA,$\r\n", context.getReceiveBuffer().toString());
    }

    // ==================== 异常处理测试 ====================

    @Test
    public void testExceptionInHandler_ShouldRemoveListener() {
        // Arrange: 设置一个会抛异常的检查函数
        listener.reset(context, mockFuture, mockSerialSource,
            response -> {
                throw new RuntimeException("Test exception");
            });

        // Act: 发送数据触发异常
        listener.onDataReceived("DATA".getBytes(), 4);

        // Assert: 异常情况下监听器应该被移除
        verify(mockFuture, times(1)).completeExceptionally(any(RuntimeException.class));
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    @Test
    public void testListenerNotInUse_ShouldIgnoreData() {
        // Arrange: 监听器未初始化（isInUse = false）
        // 不调用 reset，监听器处于未使用状态

        // Act: 发送数据
        listener.onDataReceived("DATA".getBytes(), 4);

        // Assert: 不应该有任何交互
        verify(mockFuture, never()).complete(any());
        verify(mockSerialSource, never()).removeDataListener(any());
    }

    // ==================== 实际场景模拟：PM3000EDevice ====================

    @Test
    public void testPM3000EDeviceScenario() {
        // Arrange: 模拟 PM3003EDevice 的实际响应格式
        // 第一包：大部分数据，但没有结束标记
        String packet1 = "17444,PM2.5,145.0,0.0,2332.2,105.1,0.00,27.8,34.7,0.00000,0,40.1,26.9,11.2,0.00,29.2,2816,17039.0,16750.0,0.0,0.0,0,02,0.00,0.00,";

        // 第二包：补全数据 + 结束标记
        String packet2 = "11.20,0.0$\r\n";

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.matches(".*\\$\\s*\\r?\\n") ? response : null);

        // Act 1: 第一包到达
        listener.onDataReceived(packet1.getBytes(), packet1.length());

        // Assert 1: 监听器必须保留（这是本次修复的核心！）
        verify(mockSerialSource, never()).removeDataListener(any());
        assertFalse("Response should not be finished after first packet",
            context.getFinishedFlag().get());

        // Act 2: 第二包到达（补全响应）
        listener.onDataReceived(packet2.getBytes(), packet2.length());

        // Assert 2: 完整响应被接收
        String completeResponse = packet1 + packet2;
        assertEquals("Complete response should be in buffer", completeResponse,
            context.getReceiveBuffer().toString());
        assertTrue("Response should be finished", context.getFinishedFlag().get());
        verify(mockFuture, times(1)).complete(context);

        // Assert 3: 监听器被移除
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }

    // ==================== 数据验证测试 ====================

    @Test
    public void testDataAccumulationInBuffer() {
        // Arrange: 多次追加数据
        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("END") ? response : null);

        // Act: 分多次追加
        listener.onDataReceived("PART1,".getBytes(), 6);
        listener.onDataReceived("PART2,".getBytes(), 6);
        listener.onDataReceived("PART3,".getBytes(), 6);
        listener.onDataReceived("END".getBytes(), 3);

        // Assert: 数据应该正确累积
        assertEquals("PART1,PART2,PART3,END",
            context.getReceiveBuffer().toString());
    }

    @Test
    public void testChineseCharactersInFragmentedResponse() {
        // Arrange: 测试中文字符的分片接收
        String part1 = "设备1:PM2.5,";
        String part2 = "浓度,145.0";
        String complete = part1 + part2 + "$\r\n";

        listener.reset(context, mockFuture, mockSerialSource,
            response -> response.endsWith("$\r\n") ? response : null);

        // Act: 分片发送
        listener.onDataReceived(part1.getBytes(), part1.getBytes().length);
        listener.onDataReceived(part2.getBytes(), part2.getBytes().length);
        listener.onDataReceived("$\r\n".getBytes(), 3);

        // Assert
        assertEquals(complete, context.getReceiveBuffer().toString());
        verify(mockSerialSource, times(1)).removeDataListener(listener);
    }
}
