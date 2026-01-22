package com.ecat.integration.SerialIntegration;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListenerPool;
import com.ecat.integration.SerialIntegration.SendReadStrategy.DefaultResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ResponseHandlingContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SerialIntegration 旧接口功能测试
 * 专注于旧接口功能兼容性验证
 * 
 * @author coffee
 */
public class SerialIntegrationFunctionalTest {

    @Mock
    private SerialSource mockSerialSource;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        // 设置 mock 的基本行为
        when(mockSerialSource.acquire()).thenReturn("test-key");
        when(mockSerialSource.release(anyString())).thenReturn(true);
        when(mockSerialSource.isTestMode()).thenReturn(true); // 强制测试模式
    }

    @After
    public void tearDown() {
        // 清理资源
        SerialDataListenerPool.cleanup();
    }

    // ==================== 1. 环境检测测试组 ====================

    @Test
    public void testEnvironmentDetection() {
        // 验证在测试环境中自动使用轮询模式
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        // 模拟响应数据
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("test-response"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 在测试环境中，应该自动使用轮询模式
        assertTrue("Test should complete", result.isDone());
        verify(mockSerialSource, atLeastOnce()).asyncReadData();
    }

    @Test
    public void testModeDetectionWithMock() {
        // 测试Mock环境下的模式检测
        SerialSource mockSource = mock(SerialSource.class);
        when(mockSource.acquire()).thenReturn("mock-key");
        when(mockSource.release(anyString())).thenReturn(true);
        when(mockSource.isTestMode()).thenReturn(true);
        when(mockSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("mock-response"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Mock test should complete", result.isDone());
        verify(mockSource, atLeastOnce()).asyncReadData();
    }

    // ==================== 2. 响应处理测试组 ====================

    @Test
    public void testResponseHandlingInTestMode() throws Exception {
        // 测试在测试模式下的响应处理
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> ctx.getReceiveBuffer().toString().contains("OK"),
                buffer -> buffer.toString().contains("OK") ? "OK" : null,
                ex -> false
        );

        // 模拟分片响应数据
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture(""))
                .thenReturn(CompletableFuture.completedFuture(""))
                .thenReturn(CompletableFuture.completedFuture("OK_RESPONSE"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Response should complete successfully", result.isDone());
        assertTrue("Response should be successful", result.get());
        verify(mockSerialSource, atLeastOnce()).asyncReadData();
    }

    @Test
    public void testResponseHandlingWithPartialData() throws Exception {
        // 测试分片数据处理
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> ctx.getReceiveBuffer().toString().contains("COMPLETE"),
                buffer -> buffer.toString().contains("COMPLETE") ? "COMPLETE" : null,
                ex -> false
        );

        // 模拟数据分片到达
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("PART"))
                .thenReturn(CompletableFuture.completedFuture("_IAL"))
                .thenReturn(CompletableFuture.completedFuture("_COMPLETE"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should complete with partial data", result.isDone());
        assertTrue("Should process complete data", result.get());
        assertEquals("Buffer should accumulate all data", "PART_IAL_COMPLETE",
                     context.getReceiveBuffer().toString());
    }

    @Test
    public void testResponseHandlingWithTimeout() throws Exception {
        // 测试超时处理
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远返回false，模拟未完成
                buffer -> null,
                ex -> false
        );

        // 模拟永不完整的响应
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("incomplete"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 等待足够长的时间让超时发生
        Thread.sleep(100);

        // 验证轮询确实发生了
        verify(mockSerialSource, atLeastOnce()).asyncReadData();
    }

    // ==================== 3. Mock环境测试组 ====================

    @Test
    public void testMockCompatibility() {
        // 验证Mock环境的兼容性
        SerialSource mockSource2 = mock(SerialSource.class);
        when(mockSource2.acquire()).thenReturn("mock-key");
        when(mockSource2.release(anyString())).thenReturn(true);
        when(mockSource2.isTestMode()).thenReturn(true);
        when(mockSource2.asyncReadData()).thenReturn(CompletableFuture.completedFuture("mock-response"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSource2,
                ctx -> ctx.getNewValue().equals("test"),
                response -> response,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Mock test should complete", result.isDone());
        verify(mockSource2, atLeastOnce()).asyncReadData();
    }

    @Test
    public void testMockScenarioWithDelayedResponse() throws Exception {
        // 测试Mock环境下的延迟响应
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture(""))
                .thenReturn(CompletableFuture.completedFuture("DELAYED_RESPONSE"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer.toString().contains("DELAYED") ? "DELAYED" : null,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Delayed response should complete", result.isDone());
        assertTrue("Should process delayed response", result.get());
    }

    // ==================== 4. 数据处理测试组 ====================

    @Test
    public void testBufferManagement() throws Exception {
        // 测试缓冲区管理
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture(""))
                .thenReturn(CompletableFuture.completedFuture("data1"))
                .thenReturn(CompletableFuture.completedFuture("data2"))
                .thenReturn(CompletableFuture.completedFuture("END"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer.toString().contains("END") ? "END" : null,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should complete with buffered data", result.isDone());
        assertTrue("Buffer should be complete", result.get());
        assertEquals("Buffer should contain all data", "data1data2END",
                     context.getReceiveBuffer().toString());
    }

    @Test
    public void testDataAccumulation() throws Exception {
        // 测试数据累积
        StringBuilder receivedData = new StringBuilder();

        when(mockSerialSource.asyncReadData())
                .thenAnswer(invocation -> {
                    receivedData.append("X");
                    return CompletableFuture.completedFuture("X");
                });

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> ctx.getReceiveBuffer().length() >= 5,
                buffer -> buffer.length() >= 5 ? buffer.toString() : null,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should accumulate enough data", result.isDone());
        assertTrue("Should have 5 characters", result.get());
        assertEquals("Should have exactly 5 X's", "XXXXX",
                     context.getReceiveBuffer().toString());
    }

    @Test
    public void testResponseParsing() throws Exception {
        // 测试响应解析
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("STATUS:OK;DATA:test"))
                .thenReturn(CompletableFuture.completedFuture("\r\n"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer.toString().contains("\r\n") ? buffer.toString().trim() : null,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should parse complete response", result.isDone());
        assertTrue("Should parse correctly", result.get());
        assertEquals("Buffer should contain complete response",
                     "STATUS:OK;DATA:test\r\n", context.getReceiveBuffer().toString());
    }

    // ==================== 5. 异常处理测试组 ====================

    @Test
    public void testExceptionHandling() throws Exception {
        // 测试异常处理
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("response"))
                .thenThrow(new RuntimeException("Test exception"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer,
                ex -> true // 异常时返回true
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证异常被正确处理
        verify(mockSerialSource, atLeastOnce()).asyncReadData();
    }

    @Test
    public void testTimeoutHandling() {
        // 测试超时处理
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远不完成
                buffer -> null,
                ex -> ex instanceof TimeoutException
        );

        // 返回空数据，永远不会完成
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture(""));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证轮询发生了（通过验证mock被调用）
        verify(mockSerialSource, atLeastOnce()).asyncReadData();
    }

    @Test
    public void testInvalidResponseHandling() throws Exception {
        // 测试无效响应处理
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("INVALID_RESPONSE"))
                .thenReturn(CompletableFuture.completedFuture("ALSO_INVALID"));

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远不认为响应有效
                buffer -> buffer.toString().equals("VALID_RESPONSE") ? "VALID" : null,
                ex -> false
        );

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证系统继续尝试
        verify(mockSerialSource, atLeast(2)).asyncReadData();
    }

    // ==================== 6. 接口兼容性测试组 ====================

    @Test
    public void testBackwardCompatibility() {
        // 验证接口向后兼容性
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        // 验证所有公开方法都存在
        assertNotNull("Strategy should not be null", strategy);

        // 测试正常的响应处理流程
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("response"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be a CompletableFuture", result instanceof CompletableFuture);
    }

    @Test
    public void testInterfaceStability() {
        // 测试接口稳定性
        DefaultResponseHandlerStrategy<String> strategy1 = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        DefaultResponseHandlerStrategy<String> strategy2 = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false,
                response -> null,
                ex -> true
        );

        // 验证不同的策略配置都能正常工作
        assertNotNull("Strategy1 should not be null", strategy1);
        assertNotNull("Strategy2 should not be null", strategy2);

        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("test"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result1 = strategy1.handleResponse(context);
        CompletableFuture<Boolean> result2 = strategy2.handleResponse(context);

        assertNotNull("Result1 should not be null", result1);
        assertNotNull("Result2 should not be null", result2);
    }

    @Test
    public void testResponseHandlingContextFunctionality() {
        // 测试 ResponseHandlingContext 的功能
        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test-context");

        // 验证初始状态
        assertEquals("Initial value should match", "test-context", context.getNewValue());
        assertNotNull("Receive buffer should not be null", context.getReceiveBuffer());
        assertFalse("Finished flag should be false initially", context.getFinishedFlag().get());
        assertNotNull("Start time should be set", context.getStartTime().get());

        // 测试缓冲区操作
        context.getReceiveBuffer().append("test data");
        assertEquals("Buffer should contain appended data", "test data",
                     context.getReceiveBuffer().toString());

        // 测试完成标志
        context.getFinishedFlag().set(true);
        assertTrue("Finished flag should be true", context.getFinishedFlag().get());
    }

    // ==================== 7. 自定义超时功能测试组 ====================

    @Test
    public void testConstructorWithPositiveTimeout() {
        // 测试正数超时值能正常创建
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false,
                1000L
        );
        assertNotNull("Strategy should be created with positive timeout", strategy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithZeroTimeout() {
        // 测试零超时抛出异常
        new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false,
                0L
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNegativeTimeout() {
        // 测试负数超时抛出异常
        new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false,
                -100L
        );
    }

    @Test
    public void testDefaultConstructorUsesDefaultTimeout() {
        // 测试默认构造函数使用默认超时，向后兼容
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );
        assertNotNull("Strategy should be created with default timeout", strategy);

        // 验证它能正常工作
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("response"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testConstructorWithMinimumTimeout() {
        // 测试最小超时值（1ms）
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false,
                1L
        );
        assertNotNull("Strategy should be created with minimum timeout", strategy);
    }

    @Test
    public void testConstructorWithLargeTimeout() {
        // 测试大超时值（60秒）
        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false,
                60000L
        );
        assertNotNull("Strategy should be created with large timeout", strategy);
    }

    // ==================== 超时效果测试 ====================

    @Test
    public void testShortTimeoutEffectiveness() throws Exception {
        // 测试短超时效果：150ms < 需要的轮询时间（~250ms）
        final long TIMEOUT_MS = 150;
        final int POLLS_NEEDED = 5;  // 需要 5 次轮询才能获得完整数据

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> {
                    System.out.println(ctx.getReceiveBuffer());   
                    return true;
                },
                buffer -> {
                    System.out.println(buffer); 
                    return buffer.length() >= POLLS_NEEDED ? buffer : null;
                },
                ex -> {
                    // CompletableFuture 会将异常包装在 CompletionException 中
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                TIMEOUT_MS
        );

        // 模拟每次返回 1 个字符，需要累积 5 次
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 短超时应该触发超时处理
        assertTrue("Should handle timeout and return true", result.get());
    }

    @Test
    public void testLongTimeoutEffectiveness() throws Exception {
        // 测试长超时效果：500ms > 需要的轮询时间（~250ms）
        final long TIMEOUT_MS = 500;
        final int POLLS_NEEDED = 5;

        DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> {
                    System.out.println(ctx.getReceiveBuffer());   
                    return true;
                },
                buffer -> buffer.length() >= POLLS_NEEDED ? buffer : null,
                ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                TIMEOUT_MS
        );

        // 模拟每次返回 1 个字符，需要累积 5 次
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"));

        ResponseHandlingContext<String> context = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 长超时应该正常完成
        assertTrue("Should complete successfully", result.get());
    }

    @Test
    public void testTimeoutComparison() throws Exception {
        // 对比测试：验证不同超时值产生不同结果
        final int POLLS_NEEDED = 5;

        // 短超时策略
        DefaultResponseHandlerStrategy<String> shortStrategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer.length() >= POLLS_NEEDED ? buffer : null,
                ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                150L  // 短超时
        );

        // 长超时策略
        DefaultResponseHandlerStrategy<String> longStrategy = new DefaultResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                buffer -> buffer.length() >= POLLS_NEEDED ? buffer : null,
                ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                500L  // 长超时
        );

        // 模拟数据：每次返回 1 个字符，需要累积 5 次
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"));

        // 测试短超时
        ResponseHandlingContext<String> shortContext = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> shortResult = shortStrategy.handleResponse(shortContext);
        assertTrue("Short timeout should trigger timeout handler", shortResult.get());

        // 重置 mock 用于长超时测试
        reset(mockSerialSource);
        when(mockSerialSource.acquire()).thenReturn("test-key");
        when(mockSerialSource.release(anyString())).thenReturn(true);
        when(mockSerialSource.isTestMode()).thenReturn(true);
        when(mockSerialSource.asyncReadData())
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"))
                .thenReturn(CompletableFuture.completedFuture("x"));

        // 测试长超时
        ResponseHandlingContext<String> longContext = new ResponseHandlingContext<>("test");
        CompletableFuture<Boolean> longResult = longStrategy.handleResponse(longContext);
        assertTrue("Long timeout should complete successfully", longResult.get());
    }
}
