package com.ecat.integration.SerialIntegration;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SerialIntegration 现代 API 功能测试
 * 使用推荐的现代 byte[] API 与显式字符集解码
 *
 * @author coffee
 */
public class SerialIntegrationModernFunctionalTest {

    @Mock
    private SerialSource mockSerialSource;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        // 设置 mock 的基本行为
        when(mockSerialSource.acquire()).thenReturn("test-key");
        when(mockSerialSource.release(anyString())).thenReturn(true);
        when(mockSerialSource.isTestMode()).thenReturn(true);
        when(mockSerialSource.getTimeout()).thenReturn(500);
        when(mockSerialSource.getIoExecutor()).thenReturn(java.util.concurrent.ForkJoinPool.commonPool());

        // 只使用现代 API
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture(new byte[0]));
    }

    @Test
    public void testModernAPIUsageBestPractices() {
        // 演示现代 byte[] API 的最佳实践：读字节、显式字符集解码
        AtomicReference<String> receivedData = new AtomicReference<>();

        // 使用推荐的 byte[] API 进行数据读取
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture(
                        "sensor:25.5,30.2,15.8".getBytes(StandardCharsets.US_ASCII)));

        // 直接使用现代 API 读取并解码
        CompletableFuture<String> dataFuture = mockSerialSource.asyncReadDataBytes()
                .thenApply(bytes -> {
                    String data = new String(bytes, StandardCharsets.US_ASCII);
                    receivedData.set(data);
                    return data;
                });

        // 验证数据接收
        assertTrue("Should complete successfully", dataFuture.isDone());
        assertEquals("Data should be correctly received", "sensor:25.5,30.2,15.8", receivedData.get());

        // 验证只使用了现代 API
        verify(mockSerialSource, times(1)).asyncReadDataBytes();
    }

    @Test
    public void testPureModernAPIDataProcessing() throws Exception {
        // 纯现代 API 数据处理测试
        AtomicReference<String> processedData = new AtomicReference<>();

        // 模拟传感器数据
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture(
                        "TEMP:25.5,HUM:60.2,PRES:1013.25".getBytes(StandardCharsets.US_ASCII)));

        // 使用现代 API 处理数据
        CompletableFuture<Boolean> result = mockSerialSource.asyncReadDataBytes()
                .thenApply(bytes -> {
                    String data = new String(bytes, StandardCharsets.US_ASCII);
                    processedData.set(data);

                    // 解析传感器数据
                    String[] parts = data.split(",");
                    boolean hasTemp = parts[0].contains("TEMP:");
                    boolean hasHum = parts[1].contains("HUM:");
                    boolean hasPres = parts.length > 2 && parts[2].contains("PRES:");

                    return hasTemp && hasHum && hasPres;
                });

        assertTrue("Should process data correctly", result.get());
        assertEquals("Should receive complete sensor data",
                     "TEMP:25.5,HUM:60.2,PRES:1013.25", processedData.get());

        // 验证只使用了现代 API
        verify(mockSerialSource, times(1)).asyncReadDataBytes();
    }

    @Test
    public void testTransactionStrategyBasic() throws Exception {
        // 测试基本的SerialTransactionStrategy使用
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture(
                        "test-data".getBytes(StandardCharsets.US_ASCII)));

        AtomicBoolean operationCompleted = new AtomicBoolean(false);

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                mockSerialSource,
                src -> {
                    // 验证锁已被获取
                    verify(src, times(1)).acquire();

                    return src.asyncReadDataBytes()
                            .thenApply(bytes -> {
                                String data = new String(bytes, StandardCharsets.US_ASCII);
                                operationCompleted.set(true);
                                return data.equals("test-data");
                            });
                }
        );

        // 使用 join() 确保等待 whenCompleteAsync 回调完全执行完成
        // isDone() 可能在回调被安排到线程池后就返回 true，但回调可能还没执行
        assertTrue("Result should be true", result.join());
        assertTrue("Operation should have been completed", operationCompleted.get());

        // 验证锁被释放
        verify(mockSerialSource, times(1)).release(anyString());

        // 验证只使用了现代 API
        verify(mockSerialSource, times(1)).asyncReadDataBytes();
    }

    @Test
    public void testTransactionStrategyWithException() {
        // 测试事务中的异常处理
        when(mockSerialSource.asyncReadDataBytes())
                .thenThrow(new RuntimeException("Test exception"));

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                mockSerialSource,
                src -> {
                    return src.asyncReadDataBytes()
                            .thenApply(bytes -> new String(bytes, StandardCharsets.US_ASCII))
                            .thenApply(str -> true);
                }
        );

        // 验证异常被处理
        try {
            result.get();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue("Should be caused by RuntimeException",
                      e.getCause() instanceof RuntimeException);
        }

        // 验证锁被正确释放
        verify(mockSerialSource, times(1)).release(anyString());
    }

    @Test
    public void testModernSendDataAPI() throws Exception {
        // 测试现代的数据发送 API
        byte[] testData = "AT+COMMAND\r\n".getBytes(StandardCharsets.US_ASCII);

        // 使用现代 API 发送数据
        when(mockSerialSource.asyncSendData(testData))
                .thenReturn(CompletableFuture.completedFuture(true));

        // 执行发送
        CompletableFuture<Boolean> result = mockSerialSource.asyncSendData(testData);

        assertTrue("Send should complete", result.isDone());

        // 验证使用了现代 API
        verify(mockSerialSource, times(1)).asyncSendData(testData);
    }
}
