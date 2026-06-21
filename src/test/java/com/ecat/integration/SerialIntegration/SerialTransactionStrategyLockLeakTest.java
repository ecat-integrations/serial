package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

/**
 * B5 串口锁泄漏的 TDD 单测。
 *
 * <p>问题（B5）：{@link SerialTransactionStrategy#executeWithLambda} 把 {@code release(key)} 绑定在
 * lambda 返回的 future 的 {@code whenCompleteAsync} 上。若该 future 永不 complete（模拟串口后端死亡、
 * 异步读永久挂起），{@code whenComplete} 永不触发 → release 永不执行 → 端口锁
 * （{@code SerialSourcePort.currentKey}）永久泄漏，设备掉线（&gt;1min）再上线后无法自恢复，
 * 必须重启 ecat-core（实证见 bug-record-20260619221536.md B5 节）。
 *
 * <p>参考 modbus_rtu：modbus4j {@code master.setTimeout} 保证 I/O 操作必然完成（成功或超时）→
 * {@code ModbusTransactionStrategy} 的 {@code whenComplete} 必触发 → release 必执行。纯 serial 缺此保证，
 * 故在事务层补硬超时：保证 future 必然 complete（成功或 {@link TimeoutException}），从而 release 必执行。
 *
 * <p>超时可由设备配置派生（2 参默认重载，{@link SerialTransactionStrategy#resolveDefaultTransactionTimeoutMs}），
 * 或由设备显式传入（3 参重载，对应各设备已有的 {@code .get(N, TimeUnit.SECONDS)} 事务级超时语义）。
 *
 * @author coffee
 */
public class SerialTransactionStrategyLockLeakTest {

    /** 测试用短事务超时（生产默认见 resolveDefaultTransactionTimeoutMs / Const.READ_TIMEOUT_MS） */
    private static final long TEST_TX_TIMEOUT_MS = 200L;

    /**
     * 【RED：复现 B5】lambda 返回的 future 永不 complete 时，release 必须仍被调用。
     * 修复前：release 绑在 whenCompleteAsync，future 永不 complete → whenComplete 永不触发 →
     *         release 永不执行 → 锁永久泄漏（该用例 verify 超时失败 = 复现 B5）。
     * 修复后：3 参重载用 withHardTimeout 强制 future 在 TEST_TX_TIMEOUT_MS 内 complete → release 触发。
     */
    @Test
    public void releaseFires_whenLambdaFutureNeverCompletes() throws Exception {
        SerialSource source = mock(SerialSource.class);
        String key = "test-key-leak";
        when(source.acquire()).thenReturn(key);

        // 永不 complete 的 future —— 等价于「后端死亡、异步串口读永久挂起」
        CompletableFuture<Boolean> neverCompleting = new CompletableFuture<>();

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> neverCompleting, TEST_TX_TIMEOUT_MS);

        // release 必须在硬超时 + 余量内被调用（修复前此处超时失败 = 复现 B5）
        verify(source, timeout(TEST_TX_TIMEOUT_MS + 2000).times(1)).release(key);

        // 返回的 future 必然 complete（异常 TimeoutException），调用方不被永久挂起
        try {
            result.get(TEST_TX_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            fail("期望返回 future 因事务硬超时异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应为 TimeoutException，实际: " + ee.getCause(),
                    ee.getCause() instanceof TimeoutException);
        }
    }

    /** 契约：lambda future 正常完成时，release 正常触发、结果透传。 */
    @Test
    public void releaseFires_whenLambdaFutureCompletesNormally() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-normal");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> CompletableFuture.completedFuture(true), TEST_TX_TIMEOUT_MS);

        verify(source, timeout(2000)).release("key-normal");
        assertTrue("正常完成应透传 true", result.get(2, TimeUnit.SECONDS));
    }

    /** 契约：lambda 同步抛异常时，release 在 catch 中执行（既有行为不回归）。 */
    @Test
    public void releaseFires_whenLambdaThrowsSynchronously() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn("key-throw");

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> { throw new RuntimeException("sync boom"); }, TEST_TX_TIMEOUT_MS);

        verify(source, timeout(2000)).release("key-throw");
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("期望异常完成");
        } catch (ExecutionException ee) {
            assertTrue("cause 应含同步异常信息", ee.getCause().getMessage().contains("sync boom"));
        }
    }

    /** 契约：acquire 失败（返回 null）时，不 release，返回异常 future（既有行为不回归）。 */
    @Test
    public void returnsFailedFuture_whenAcquireReturnsNull() throws Exception {
        SerialSource source = mock(SerialSource.class);
        when(source.acquire()).thenReturn(null);

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> new CompletableFuture<>(), TEST_TX_TIMEOUT_MS);

        verify(source, never()).release(any());
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("期望异常完成");
        } catch (ExecutionException ee) {
            assertTrue("acquire 失败应抛 IllegalStateException",
                    ee.getCause() instanceof IllegalStateException);
        }
    }

    /**
     * 契约：默认（2 参重载）事务硬超时由设备配置的串口超时派生（× {@link SerialTransactionStrategy#TRANSACTION_TIMEOUT_FACTOR}），
     * 与现有 DefaultResponseHandlerStrategy / ByteResponseHandlerStrategy 的
     * {@code source.getTimeout() > 0 ? source.getTimeout() : Const.READ_TIMEOUT_MS} 模式一致。
     */
    @Test
    public void defaultTimeout_derivedFromDeviceConfigTimeout() {
        SerialSource source = mock(SerialSource.class);
        when(source.getTimeout()).thenReturn(300);

        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(source);

        assertEquals((long) (300 * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }

    /** 契约：设备串口超时无效（≤0）时，默认事务超时回退 Const.READ_TIMEOUT_MS × 倍数。 */
    @Test
    public void defaultTimeout_fallsBackToConst_whenDeviceTimeoutInvalid() {
        SerialSource source = mock(SerialSource.class);
        when(source.getTimeout()).thenReturn(0);

        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(source);

        assertEquals((long) (Const.READ_TIMEOUT_MS * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }

    /** 契约：source 为 null 时不应 NPE，回退 Const.READ_TIMEOUT_MS × 倍数。 */
    @Test
    public void defaultTimeout_fallsBackToConst_whenSourceNull() {
        long resolved = SerialTransactionStrategy.resolveDefaultTransactionTimeoutMs(null);
        assertEquals((long) (Const.READ_TIMEOUT_MS * SerialTransactionStrategy.TRANSACTION_TIMEOUT_FACTOR), resolved);
    }
}
