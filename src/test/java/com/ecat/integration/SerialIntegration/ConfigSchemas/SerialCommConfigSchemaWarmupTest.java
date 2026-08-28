package com.ecat.integration.SerialIntegration.ConfigSchemas;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 枚举缓存 + 首载串行化契约测试（bug-record-20260826-003500）。
 *
 * <p>被测契约：
 * <ul>
 *   <li>并发首触 getAvailablePorts：jSerialComm 首载竞态的入口收敛——两线程同时首调，
 *       枚举源只被触达一次（锁内单线程完成，另一线程复用缓存）。</li>
 *   <li>warmUp() 结果进 TTL 缓存：预热后的 getAvailablePorts 不再触达枚举源。</li>
 *   <li>resetEnumerationSeams 清空缓存（测试隔离）。</li>
 *   <li>TTL=0 时每次调用重新枚举（热插拔可见性的下界语义）。</li>
 * </ul>
 *
 * <p>同步纪律：CyclicBarrier 确定性并发起跑，无 Thread.sleep。
 *
 * @author coffee
 */
public class SerialCommConfigSchemaWarmupTest {

    @Before
    public void setUp() {
        SerialCommConfigSchema.resetEnumerationSeams();
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    @After
    public void tearDown() {
        SerialCommConfigSchema.resetEnumerationSeams();
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    /** 红测核心（003500 形态）：两线程同时首调 getAvailablePorts，枚举源只触达一次。 */
    @Test(timeout = 15000)
    public void concurrentFirstTouchEnumeratesExactlyOnce() throws Exception {
        AtomicInteger apiInvocations = new AtomicInteger();
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> {
            apiInvocations.incrementAndGet();
            return ports("ttyUSB161");
        };
        SerialCommConfigSchema.devNodeScanSupplier = LinkedHashMap::new;
        SerialCommConfigSchema.windowsDetector = () -> false;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier startGate = new CyclicBarrier(2);
            Future<Map<String, String>> a = pool.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                return SerialCommConfigSchema.getAvailablePorts();
            });
            Future<Map<String, String>> b = pool.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                return SerialCommConfigSchema.getAvailablePorts();
            });
            Map<String, String> resultA = a.get(10, TimeUnit.SECONDS);
            Map<String, String> resultB = b.get(10, TimeUnit.SECONDS);

            assertEquals("并发首触只允许一次真实枚举（首载串行化 + TTL 缓存）", 1, apiInvocations.get());
            assertEquals("两线程应得到同一枚举结果", "ttyUSB161", firstPortKey(resultA));
            assertEquals("两线程应得到同一枚举结果", "ttyUSB161", firstPortKey(resultB));
        } finally {
            pool.shutdownNow();
        }
    }

    /** warmUp() 后的读路径不再触达枚举源（缓存命中）。 */
    @Test(timeout = 15000)
    public void warmUpPopulatesCacheForSubsequentReads() {
        SerialCommConfigSchema.warmUp();
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> {
            throw new AssertionError("TTL 缓存命中时不应再触达枚举源");
        };
        SerialCommConfigSchema.devNodeScanSupplier = LinkedHashMap::new;
        // 不抛异常即通过：getAvailablePorts 走缓存
        SerialCommConfigSchema.getAvailablePorts();
    }

    /** resetEnumerationSeams 清空缓存：重设枚举源后立即生效（测试隔离语义）。 */
    @Test(timeout = 15000)
    public void resetSeamsInvalidatesCache() {
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> ports("ttyUSB100");
        SerialCommConfigSchema.devNodeScanSupplier = LinkedHashMap::new;
        SerialCommConfigSchema.windowsDetector = () -> false;
        SerialCommConfigSchema.getAvailablePorts();

        SerialCommConfigSchema.resetEnumerationSeams();
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> ports("ttyUSB200");
        SerialCommConfigSchema.devNodeScanSupplier = LinkedHashMap::new;
        SerialCommConfigSchema.windowsDetector = () -> false;
        Map<String, String> result = SerialCommConfigSchema.getAvailablePorts();
        assertEquals("reset 后缓存已清空，新枚举源立即生效", "ttyUSB200", firstPortKey(result));
    }

    /** TTL=0：每次调用都重新枚举（热插拔新口无 TTL 滞留的下界语义）。 */
    @Test(timeout = 15000)
    public void zeroTtlForcesReEnumeration() {
        SerialCommConfigSchema.enumerationCacheTtlMs = 0L;
        try {
            AtomicInteger apiInvocations = new AtomicInteger();
            SerialCommConfigSchema.apiPortOptionsSupplier = () -> {
                apiInvocations.incrementAndGet();
                return ports("ttyUSB161");
            };
            SerialCommConfigSchema.devNodeScanSupplier = LinkedHashMap::new;
            SerialCommConfigSchema.windowsDetector = () -> false;
            SerialCommConfigSchema.getAvailablePorts();
            SerialCommConfigSchema.getAvailablePorts();
            assertEquals("TTL=0 时不允许复用缓存", 2, apiInvocations.get());
        } finally {
            SerialCommConfigSchema.enumerationCacheTtlMs = 5000L;
        }
    }

    private static Map<String, String> ports(String... names) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, name);
        }
        return result;
    }

    /** 取首个真实端口 key（跳过 "" 提示头）。 */
    private static String firstPortKey(Map<String, String> decorated) {
        for (String key : decorated.keySet()) {
            if (!key.isEmpty()) {
                return key;
            }
        }
        throw new AssertionError("枚举结果应含至少一个真实端口");
    }

}
