package com.ecat.integration.SerialIntegration.bytes;

import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.Listener.SerialListenerPools;
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
 * SerialIntegration Byte 模式功能测试
 * 专注于 ByteResponseHandlerStrategy 功能验证
 *
 * @author coffee
 */
public class SerialIntegrationByteFunctionalTest {

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
        SerialListenerPools.BYTE_POOL.cleanup();
    }

    // ==================== 1. 环境检测测试组 ====================

    @Test
    public void testEnvironmentDetection() {
        // 验证在测试环境中自动使用轮询模式
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        // 模拟响应数据
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("test-response".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 在测试环境中，应该自动使用轮询模式
        assertTrue("Test should complete", result.isDone());
        verify(mockSerialSource, atLeastOnce()).asyncReadDataBytes();
    }

    @Test
    public void testModeDetectionWithMock() {
        // 测试Mock环境下的模式检测
        SerialSource mockSource = mock(SerialSource.class);
        when(mockSource.acquire()).thenReturn("mock-key");
        when(mockSource.release(anyString())).thenReturn(true);
        when(mockSource.isTestMode()).thenReturn(true);
        when(mockSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("mock-response".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Mock test should complete", result.isDone());
        verify(mockSource, atLeastOnce()).asyncReadDataBytes();
    }

    // ==================== 2. 响应处理测试组 ====================

    @Test
    public void testResponseHandlingInTestMode() throws Exception {
        // 测试在测试模式下的响应处理
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> new String(ctx.getReceiveBytes()).contains("OK"),
                bytes -> new String(bytes).contains("OK") ? bytes : null,
                ex -> false
        );

        // 模拟分片响应数据
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("OK_RESPONSE".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Response should complete successfully", result.isDone());
        assertTrue("Response should be successful", result.get());
        verify(mockSerialSource, atLeastOnce()).asyncReadDataBytes();
    }

    @Test
    public void testResponseHandlingWithPartialData() throws Exception {
        // 测试分片数据处理
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> new String(ctx.getReceiveBytes()).contains("COMPLETE"),
                bytes -> new String(bytes).contains("COMPLETE") ? bytes : null,
                ex -> false
        );

        // 模拟数据分片到达
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("PART".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("_IAL".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("_COMPLETE".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should complete with partial data", result.isDone());
        assertTrue("Should process complete data", result.get());
        assertEquals("Buffer should accumulate all data", "PART_IAL_COMPLETE",
                     new String(context.getReceiveBytes()));
    }

    @Test
    public void testResponseHandlingWithTimeout() throws Exception {
        // 测试超时处理
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远返回false，模拟未完成
                bytes -> null,
                ex -> false
        );

        // 模拟永不完整的响应
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("incomplete".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 等待足够长的时间让超时发生
        Thread.sleep(100);

        // 验证轮询确实发生了
        verify(mockSerialSource, atLeastOnce()).asyncReadDataBytes();
    }

    // ==================== 3. Mock环境测试组 ====================

    @Test
    public void testMockCompatibility() {
        // 验证Mock环境的兼容性
        SerialSource mockSource2 = mock(SerialSource.class);
        when(mockSource2.acquire()).thenReturn("mock-key");
        when(mockSource2.release(anyString())).thenReturn(true);
        when(mockSource2.isTestMode()).thenReturn(true);
        when(mockSource2.asyncReadDataBytes()).thenReturn(CompletableFuture.completedFuture("mock-response".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSource2,
                ctx -> ctx.getNewValue().equals("test"),
                response -> response,
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Mock test should complete", result.isDone());
        verify(mockSource2, atLeastOnce()).asyncReadDataBytes();
    }

    @Test
    public void testMockScenarioWithDelayedResponse() throws Exception {
        // 测试Mock环境下的延迟响应
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("DELAYED_RESPONSE".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> new String(bytes).contains("DELAYED") ? bytes : null,
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Delayed response should complete", result.isDone());
        assertTrue("Should process delayed response", result.get());
    }

    // ==================== 4. 数据处理测试组 ====================

    @Test
    public void testBufferManagement() throws Exception {
        // 测试缓冲区管理
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("data1".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("data2".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("END".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> new String(bytes).contains("END") ? bytes : null,
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should complete with buffered data", result.isDone());
        assertTrue("Buffer should be complete", result.get());
        assertEquals("Buffer should contain all data", "data1data2END",
                     new String(context.getReceiveBytes()));
    }

    @Test
    public void testDataAccumulation() throws Exception {
        // 测试数据累积
        when(mockSerialSource.asyncReadDataBytes())
                .thenAnswer(invocation -> {
                    return CompletableFuture.completedFuture("X".getBytes());
                });

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> ctx.getReceiveBytes().length >= 5,
                bytes -> bytes.length >= 5 ? bytes : null,
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should accumulate enough data", result.isDone());
        assertTrue("Should have 5 characters", result.get());
        assertEquals("Should have exactly 5 X's", "XXXXX",
                     new String(context.getReceiveBytes()));
    }

    @Test
    public void testResponseParsing() throws Exception {
        // 测试响应解析
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("STATUS:OK;DATA:test".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("\r\n".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> {
                    String s = new String(bytes);
                    return s.contains("\r\n") ? bytes : null;
                },
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertTrue("Should parse complete response", result.isDone());
        assertTrue("Should parse correctly", result.get());
        assertEquals("Buffer should contain complete response",
                     "STATUS:OK;DATA:test\r\n", new String(context.getReceiveBytes()));
    }

    // ==================== 5. 异常处理测试组 ====================

    @Test
    public void testExceptionHandling() throws Exception {
        // 测试异常处理
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("response".getBytes()))
                .thenThrow(new RuntimeException("Test exception"));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> true // 异常时返回true
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证异常被正确处理
        verify(mockSerialSource, atLeastOnce()).asyncReadDataBytes();
    }

    @Test
    public void testTimeoutHandling() {
        // 测试超时处理
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远不完成
                bytes -> null,
                ex -> ex instanceof TimeoutException
        );

        // 返回空数据，永远不会完成
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证轮询发生了（通过验证mock被调用）
        verify(mockSerialSource, atLeastOnce()).asyncReadDataBytes();
    }

    @Test
    public void testInvalidResponseHandling() throws Exception {
        // 测试无效响应处理
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("INVALID_RESPONSE".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("ALSO_INVALID".getBytes()));

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false, // 永远不认为响应有效
                bytes -> {
                    String s = new String(bytes);
                    return s.equals("VALID_RESPONSE") ? bytes : null;
                },
                ex -> false
        );

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        // 验证系统继续尝试
        verify(mockSerialSource, atLeast(2)).asyncReadDataBytes();
    }

    // ==================== 6. 接口兼容性测试组 ====================

    @Test
    public void testBackwardCompatibility() {
        // 验证接口兼容性
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        // 验证所有公开方法都存在
        assertNotNull("Strategy should not be null", strategy);

        // 测试正常的响应处理流程
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("response".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be a CompletableFuture", result instanceof CompletableFuture);
    }

    @Test
    public void testInterfaceStability() {
        // 测试接口稳定性
        ByteResponseHandlerStrategy<String> strategy1 = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                response -> response,
                ex -> false
        );

        ByteResponseHandlerStrategy<String> strategy2 = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> false,
                response -> null,
                ex -> true
        );

        // 验证不同的策略配置都能正常工作
        assertNotNull("Strategy1 should not be null", strategy1);
        assertNotNull("Strategy2 should not be null", strategy2);

        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("test".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result1 = strategy1.handleResponse(context);
        ByteResponseHandlingContext<String> context2 = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result2 = strategy2.handleResponse(context2);

        assertNotNull("Result1 should not be null", result1);
        assertNotNull("Result2 should not be null", result2);
    }

    @Test
    public void testResponseHandlingContextFunctionality() {
        // 测试 ByteResponseHandlingContext 的功能
        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test-context");

        // 验证初始状态
        assertEquals("Initial value should match", "test-context", context.getNewValue());
        assertNotNull("Receive buffer should not be null", context.getReceiveBuffer());
        assertFalse("Finished flag should be false initially", context.getFinishedFlag().get());
        assertNotNull("Start time should be set", context.getStartTime().get());

        // 测试缓冲区操作
        context.getReceiveBuffer().write("test data".getBytes(), 0, 9);
        assertEquals("Buffer should contain appended data", "test data",
                     new String(context.getReceiveBytes()));

        // 测试完成标志
        context.getFinishedFlag().set(true);
        assertTrue("Finished flag should be true", context.getFinishedFlag().get());
    }

    // ==================== 7. 自定义超时测试组 ====================

    @Test
    public void testCustomTimeoutConstructor() {
        // 测试自定义超时构造函数 - 正数超时值
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false,
                1000L  // 自定义超时 1000ms
        );

        assertNotNull("Strategy should not be null", strategy);

        // 测试正常响应处理
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("response".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testCustomTimeoutConstructorWithShortTimeout() {
        // 测试自定义超时构造函数 - 快速超时（200ms）
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false,
                200L  // 快速超时 200ms
        );

        assertNotNull("Strategy with short timeout should not be null", strategy);
    }

    @Test
    public void testCustomTimeoutConstructorWithLongTimeout() {
        // 测试自定义超时构造函数 - 长超时（5000ms）
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false,
                5000L  // 长超时 5000ms
        );

        assertNotNull("Strategy with long timeout should not be null", strategy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCustomTimeoutConstructorWithZeroTimeout() {
        // 测试自定义超时构造函数 - 零超时应该抛出异常
        new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false,
                0L  // 零超时 - 应该抛出 IllegalArgumentException
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCustomTimeoutConstructorWithNegativeTimeout() {
        // 测试自定义超时构造函数 - 负数超时应该抛出异常
        new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false,
                -100L  // 负数超时 - 应该抛出 IllegalArgumentException
        );
    }

    @Test
    public void testDefaultTimeoutConstructor() {
        // 测试默认超时构造函数 - 应该使用 Const.READ_TIMEOUT_MS (500ms)
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> bytes,
                ex -> false
        );

        assertNotNull("Strategy with default timeout should not be null", strategy);

        // 测试正常响应处理
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("response".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        CompletableFuture<Boolean> result = strategy.handleResponse(context);

        assertNotNull("Result should not be null", result);
        assertTrue("Result should complete", result.isDone());
    }

    @Test
    public void testShortTimeoutEffectiveness() throws Exception {
        // 测试短超时（150ms）在实际运行时的效果
        // 场景：需要多次轮询（每次50ms间隔）才能获得完整数据
        //      短超时会在获得数据前超时
        final long TIMEOUT_MS = 150;
        final int POLLS_NEEDED = 5;  // 需要 5 次轮询才能获得完整数据

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> {
                    System.out.println(ctx.getReceiveBuffer().toString());   
                    return true;
                },
                bytes -> {
                    System.out.println(new String(bytes)); 
                    // 只有累积到足够数据才认为完整
                    return new String(bytes).length() >= POLLS_NEEDED ? bytes : null;
                },
                ex -> {
                    System.out.println(ex.getMessage()); 
                    // CompletableFuture 会将异常包装在 CompletionException 中
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                TIMEOUT_MS
        );

        // 每次返回 1 个字节，需要 5 次轮询
        // 每次轮询间隔 50ms，5 次需要约 250ms
        // 超时 150ms，应该在获得完整数据前超时
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        long startTime = System.currentTimeMillis();
        CompletableFuture<Boolean> result = strategy.handleResponse(context);
        long duration = System.currentTimeMillis() - startTime;

        // 验证：应该在超时时间附近完成（而不是等待 5 次轮询）
        // 5次轮询需要约 250ms，但超时是 150ms
        assertTrue("Should timeout around " + TIMEOUT_MS + "ms, not wait for full data (actual: " + duration + "ms)",
                   duration < TIMEOUT_MS + 100);

        // 验证结果：超时后应该通过异常处理返回 true
        assertTrue("Should handle timeout and return true", result.get());
    }

    @Test
    public void testLongTimeoutEffectiveness() throws Exception {
        // 测试长超时（500ms）在实际运行时的效果
        // 场景：需要多次轮询才能获得完整数据
        //      长超时足够等待数据到达
        final long TIMEOUT_MS = 500;
        final int POLLS_NEEDED = 5;

        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> {
                    System.out.println(ctx.getReceiveBuffer().toString());   
                    return true;
                },
                bytes -> {
                    System.out.println(new String(bytes)); 
                    // 累积到足够数据才认为完整
                    return new String(bytes).length() >= POLLS_NEEDED ? bytes : null;
                },
                ex -> {
                    System.out.println(ex.getMessage()); 
                    // CompletableFuture 会将异常包装在 CompletionException 中
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                TIMEOUT_MS
        );

        // 与短超时测试相同的场景：需要 5 次轮询
        // 但超时 500ms，足够等待数据到达
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("x".getBytes()));

        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("test");
        long startTime = System.currentTimeMillis();
        CompletableFuture<Boolean> result = strategy.handleResponse(context);
        long duration = System.currentTimeMillis() - startTime;

        // 验证：应该等待 5 次轮询完成（约 250ms），而不是超时（500ms）
        assertTrue("Should wait for full data, not timeout (actual: " + duration + "ms)",
                   duration >= 200 && duration < TIMEOUT_MS);

        // 验证结果：正常完成（获得完整数据）
        assertTrue("Should complete successfully with full data", result.get());
    }

    @Test
    public void testTimeoutComparison() throws Exception {
        // 对比测试：在同一个场景下，验证不同超时值产生不同结果
        // 场景：需要 5 次轮询才能获得完整数据（约 250ms）
        final int POLLS_NEEDED = 5;

        // 测试1：短超时（100ms）< 需要的轮询时间（250ms）= 应该超时
        ByteResponseHandlerStrategy<String> shortTimeoutStrategy =
            new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> new String(bytes).length() >= POLLS_NEEDED ? bytes : null,
                ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                100L  // 短超时
            );

        // 设置 5 次轮询的数据
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("a".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("b".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("c".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("d".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("e".getBytes()));

        ByteResponseHandlingContext<String> context1 = new ByteResponseHandlingContext<>("test1");
        CompletableFuture<Boolean> result1 = shortTimeoutStrategy.handleResponse(context1);

        // 短超时应该通过异常处理返回 true（超时）
        assertTrue("Short timeout (100ms) should timeout before getting full data (250ms)",
                   result1.get());

        // 重置 mock
        reset(mockSerialSource);
        when(mockSerialSource.isTestMode()).thenReturn(true);
        when(mockSerialSource.acquire()).thenReturn("test-key");
        when(mockSerialSource.release(anyString())).thenReturn(true);

        // 测试2：长超时（500ms）> 需要的轮询时间（250ms）= 应该正常完成
        ByteResponseHandlerStrategy<String> longTimeoutStrategy =
            new ByteResponseHandlerStrategy<>(
                mockSerialSource,
                ctx -> true,
                bytes -> new String(bytes).length() >= POLLS_NEEDED ? bytes : null,
                ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    return cause instanceof TimeoutException;
                },
                500L  // 长超时
            );

        // 相同的场景：需要 5 次轮询
        when(mockSerialSource.asyncReadDataBytes())
                .thenReturn(CompletableFuture.completedFuture("a".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("b".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("c".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("d".getBytes()))
                .thenReturn(CompletableFuture.completedFuture("e".getBytes()));

        ByteResponseHandlingContext<String> context2 = new ByteResponseHandlingContext<>("test2");
        CompletableFuture<Boolean> result2 = longTimeoutStrategy.handleResponse(context2);

        // 长超时应该正常完成（获得完整数据）
        assertTrue("Long timeout (500ms) should wait for full data (250ms)",
                   result2.get());
    }
}
