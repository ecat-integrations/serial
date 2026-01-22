package com.ecat.integration.SerialIntegration.bytes;

import com.ecat.core.Utils.LogFactory;
import com.ecat.core.Utils.Log;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialListenerPools;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 串口 Listener 池耗尽问题复现测试
 *
 * 目标：复现 ByteResponseHandlerStrategy 在超时情况下 listener 泄漏的问题
 *
 * 问题根因分析：
 * 1. 超时任务执行时释放 listener (ByteResponseHandlerStrategy:113)
 * 2. whenComplete 始终执行，再次释放 listener (ByteResponseHandlerStrategy:140)
 * 3. 重复释放导致 listener 无法正确归还到 ThreadLocal 池的可用队列
 *
 * 测试场景：
 * - 场景1：单线程连续超时，快速复现池耗尽，如果server延迟600ms返回，而client500ms超时，导致下一次收到上一次的脏数据，现象是成功一次失败一次。如果都是510ms则全部超时
 * - 场景2：单线程持续异常的兼容处理
 *
 * 前置条件：
 * socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1
 *
 */
public class ListenerPoolExhaustionReproductionTest {

    private static final Log log = LogFactory.getLogger(ListenerPoolExhaustionReproductionTest.class);

    // ==================== 常量配置 ====================
    private static final int POOL_SIZE = 20;  // SerialListenerPools.BYTE_POOL 的配置大小
    private static final int READ_TIMEOUT_MS = 500;  // Const.READ_TIMEOUT_MS
    private static final int SERVER_DELAY_MS = 510;  // Server 响应延迟，确保超时

    // ==================== 测试端口配置 ====================
    private static final String SERVER_PORT = "/dev/ttyV0";
    private static final String CLIENT_PORT = "/dev/ttyV1";

    // ==================== 统计数据 ====================
    private static final AtomicInteger totalRequests = new AtomicInteger(0);
    private static final AtomicInteger timeoutCount = new AtomicInteger(0);
    private static final AtomicInteger poolExhaustionCount = new AtomicInteger(0);
    private static final AtomicInteger tempListenerUsedCount = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);

    // ==================== 时间戳格式化器 ====================
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 获取带毫秒时间戳的日志消息
     */
    private static String logWithTimestamp(String message) {
        return "[" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "] " + message;
    }

    /**
     * 打印分隔线
     */
    private static void printSeparator(char c, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(c);
        }
        System.out.println(sb.toString());
    }

    /**
     * 打印当前 listener 池状态
     */
    private static void printPoolStatus(String phase) {
        int available = SerialListenerPools.BYTE_POOL.getAvailableCount();
        int inUse = SerialListenerPools.BYTE_POOL.getInUseCount();
        int total = SerialListenerPools.BYTE_POOL.getTotalCount();

        System.out.printf(logWithTimestamp("[%s] Pool状态 - 可用: %d/%d, 使用中: %d, 总计: %d%n"),
            phase, available, POOL_SIZE, inUse, total);
    }

    public static void main(String[] args) throws Exception {
        // 强制使用生产模式（中断驱动），而不是测试模式（轮询模式）
        System.setProperty("test.mode", "false");

        printSeparator('=', 70);
        System.out.println(logWithTimestamp("=== 串口 Listener 池耗尽问题复现测试 ==="));
        printSeparator('=', 70);
        System.out.println("前置条件：请确保已执行以下命令创建虚拟串口");
        System.out.println("  socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1");
        System.out.println();

        // 初始化串口
        SerialSource serverSource = null;
        SerialSource clientSource = null;

        try {
            // 1. 初始化串口连接
            System.out.println(logWithTimestamp("初始化串口连接..."));
            SerialInfo serverInfo = new SerialInfo(SERVER_PORT, 9600, 8, 1, 0);
            SerialInfo clientInfo = new SerialInfo(CLIENT_PORT, 9600, 8, 1, 0);

            serverSource = new SerialSource(serverInfo);
            clientSource = new SerialSource(clientInfo);

            serverSource.registerIntegration("test-server");
            clientSource.registerIntegration("test-client");

            if (!serverSource.isPortOpen() || !clientSource.isPortOpen()) {
                throw new RuntimeException("串口打开失败");
            }

            System.out.println(logWithTimestamp("串口初始化成功"));
            System.out.println("  Server: " + SERVER_PORT);
            System.out.println("  Client: " + CLIENT_PORT);
            System.out.println("  池大小: " + POOL_SIZE);
            System.out.println("  超时时间: " + READ_TIMEOUT_MS + "ms");
            System.out.println("  Server延迟: " + SERVER_DELAY_MS + "ms");
            System.out.println();

            // 2. 设置 Server 端自动响应（延迟响应确保超时）
            System.out.println(logWithTimestamp("设置 Server 端自动响应..."));
            setupDelayedServerResponder(serverSource);
            System.out.println(logWithTimestamp("Server 端已配置，响应延迟: " + SERVER_DELAY_MS + "ms"));
            System.out.println();

            // 等待 server 准备就绪
            Thread.sleep(500);

            // 3. 执行场景1：单线程连续超时（快速复现）
            printSeparator('=', 70);
            System.out.println(logWithTimestamp("场景1：单线程连续超时测试"));
            printSeparator('=', 70);
            runSingleThreadHighFrequencyTest(clientSource);

            // 重置统计
            resetStatistics();
            Thread.sleep(2000);

            // 切换服务器到正常响应模式
            System.out.println(logWithTimestamp("切换 Server 端到正常响应模式..."));
            serverSource.removeAllDataListeners();
            setupNormalServerResponder(serverSource);
            System.out.println(logWithTimestamp("Server 端已切换到正常响应模式"));
            System.out.println();

            // 4. 执行场景2：异常安全性测试
            printSeparator('=', 70);
            System.out.println(logWithTimestamp("场景2：异常安全性测试"));
            printSeparator('=', 70);
            runExceptionSafetyTest(clientSource);

            // 5. 打印最终报告
            printSeparator('=', 70);
            System.out.println(logWithTimestamp("=== 测试完成 ==="));
            printSeparator('=', 70);
            printFinalReport();

        } finally {
            // 清理资源
            System.out.println(logWithTimestamp("清理资源..."));
            if (serverSource != null) {
                serverSource.removeAllDataListeners();
                serverSource.closePort("test-server");
            }
            if (clientSource != null) {
                clientSource.removeAllDataListeners();
                clientSource.closePort("test-client");
            }
            SerialTimeoutScheduler.cleanupScheduler(CLIENT_PORT);
            SerialListenerPools.BYTE_POOL.cleanup();
            System.out.println(logWithTimestamp("资源清理完成"));
        }
    }

    /**
     * 场景1：单线程高频超时测试
     *
     * 设计思路：
     * - 在同一个线程中连续发起 25 次请求（超过池大小 20）
     * - Server 延迟 600ms 响应，确保每次都超时（READ_TIMEOUT_MS = 500ms）
     * - 预期：在 20-25 次请求后出现 "No pooled byte listener available"
     */
    private static void runSingleThreadHighFrequencyTest(SerialSource clientSource)
            throws Exception {

        final int REQUEST_COUNT = 25;  // 超过池大小 20
        final long REQUEST_INTERVAL_MS = 10;  // 极短间隔，确保快速连续发送，来不及等响应

        System.out.println(logWithTimestamp("测试配置:"));
        System.out.println("  请求次数: " + REQUEST_COUNT);
        System.out.println("  请求间隔: " + REQUEST_INTERVAL_MS + "ms");
        System.out.println("  超时时间: " + READ_TIMEOUT_MS + "ms");
        System.out.println("  Server延迟: " + SERVER_DELAY_MS + "ms");
        System.out.println("  池大小: " + POOL_SIZE);
        System.out.println();

        printPoolStatus("初始");

        List<Long> availableCounts = new ArrayList<>();
        List<String> results = new ArrayList<>();

        for (int i = 1; i <= REQUEST_COUNT; i++) {
            long requestStartTime = System.currentTimeMillis();

            // 记录请求前的池状态
            int availableBefore = SerialListenerPools.BYTE_POOL.getAvailableCount();
            availableCounts.add((long) availableBefore);

            System.out.printf(logWithTimestamp("[请求 %2d/%2d] 发送前 - 可用: %d/%d%n"),
                i, REQUEST_COUNT, availableBefore, POOL_SIZE);

            try {
                // 执行请求
                boolean success = executeRequest(clientSource, i, "SingleThread");

                if (success) {
                    successCount.incrementAndGet();
                    results.add("SUCCESS");
                } else {
                    results.add("TIMEOUT");
                }

                // 记录请求后的池状态
                int availableAfter = SerialListenerPools.BYTE_POOL.getAvailableCount();
                System.out.printf(logWithTimestamp("[请求 %2d/%2d] 完成后 - 可用: %d/%d, 结果: %s%n"),
                    i, REQUEST_COUNT, availableAfter, POOL_SIZE, results.get(i - 1));

            } catch (TimeoutException e) {
                timeoutCount.incrementAndGet();
                results.add("TIMEOUT_EXCEPTION");
                int availableAfter = SerialListenerPools.BYTE_POOL.getAvailableCount();
                System.out.printf(logWithTimestamp("[请求 %2d/%2d] 超时异常 - 可用: %d/%d%n"),
                    i, REQUEST_COUNT, availableAfter, POOL_SIZE);

            } catch (Exception e) {
                results.add("ERROR: " + e.getClass().getSimpleName());
                System.err.println(logWithTimestamp("[请求 " + i + "] 异常: " + e.getMessage()));
            }

            totalRequests.incrementAndGet();

            // 如果池已耗尽，记录
            int availableNow = SerialListenerPools.BYTE_POOL.getAvailableCount();
            if (availableNow == 0) {
                poolExhaustionCount.incrementAndGet();
                System.err.println(logWithTimestamp(">>> 池耗尽！可用: " + availableNow + "/" + POOL_SIZE));
            }

            // 等待间隔（确保前一个请求已超时完成）
            long elapsed = System.currentTimeMillis() - requestStartTime;
            long remainingWait = Math.max(0, REQUEST_INTERVAL_MS - elapsed);
            if (remainingWait > 0 && i < REQUEST_COUNT) {
                Thread.sleep(remainingWait);
            }
        }

        // 打印结果统计
        System.out.println();
        printSeparator('-', 70);
        System.out.println(logWithTimestamp("场景1结果统计:"));
        printSeparator('-', 70);

        System.out.println("请求序列:");
        for (int i = 0; i < REQUEST_COUNT; i++) {
            System.out.printf("  [%2d] 可用=%2d/%2d -> 结果=%s%n",
                i + 1, availableCounts.get(i), POOL_SIZE, results.get(i));
        }

        System.out.println();
        System.out.printf("总请求数: %d%n", REQUEST_COUNT);
        System.out.printf("成功: %d%n", successCount.get());
        System.out.printf("超时: %d%n", timeoutCount.get());
        System.out.printf("池耗尽次数: %d%n", poolExhaustionCount.get());
        System.out.printf("临时监听器使用: %d%n", tempListenerUsedCount.get());
        System.out.println();

        printPoolStatus("场景1结束");

        // 判断是否复现了问题
        if (poolExhaustionCount.get() > 0) {
            System.err.println(">>> 问题复现：检测到 listener 池耗尽！");
        } else {
            System.out.println("未检测到池耗尽（可能已修复）");
        }
    }

    /**
     * 场景2：异常安全性测试
     *
     * 设计思路：
     * - 单线程执行，避免串口锁并发问题
     * - 服务器正常响应（不超时）
     * - handleExceptionFunction 故意抛出 RuntimeException
     * - 验证：whenCompleteAsync 中的 release 一定会执行
     * - 预期：每次请求后，监听器池状态恢复为 20/20
     */
    private static void runExceptionSafetyTest(SerialSource clientSource)
            throws Exception {

        final int REQUEST_COUNT = 10;  // 测试 10 次异常
        final long REQUEST_INTERVAL_MS = 200;  // 间隔等待前一个请求完成

        System.out.println(logWithTimestamp("测试配置:"));
        System.out.println("  请求次数: " + REQUEST_COUNT);
        System.out.println("  请求间隔: " + REQUEST_INTERVAL_MS + "ms");
        System.out.println("  测试场景: handleExceptionFunction 抛出异常");
        System.out.println("  预期结果: 每次请求后池状态恢复 20/20");
        System.out.println();

        printPoolStatus("场景2初始");

        List<Long> availableCounts = new ArrayList<>();
        List<String> results = new ArrayList<>();
        AtomicInteger exceptionInHandlerCount = new AtomicInteger(0);

        for (int i = 1; i <= REQUEST_COUNT; i++) {
            long requestStartTime = System.currentTimeMillis();

            // 记录请求前的池状态
            int availableBefore = SerialListenerPools.BYTE_POOL.getAvailableCount();
            availableCounts.add((long) availableBefore);

            System.out.printf(logWithTimestamp("[请求 %2d/%2d] 发送前 - 可用: %d/%d%n"),
                i, REQUEST_COUNT, availableBefore, POOL_SIZE);

            try {
                // 执行请求，handleExceptionFunction 会抛异常
                boolean success = executeRequestWithExceptionInHandler(clientSource, i, "ExceptionTest");

                if (success) {
                    successCount.incrementAndGet();
                    results.add("SUCCESS");
                } else {
                    results.add("FALSE");
                }

                // 记录请求后的池状态
                int availableAfter = SerialListenerPools.BYTE_POOL.getAvailableCount();
                System.out.printf(logWithTimestamp("[请求 %2d/%2d] 完成后 - 可用: %d/%d, 结果: %s%n"),
                    i, REQUEST_COUNT, availableAfter, POOL_SIZE, results.get(i - 1));

                // 验证池状态是否恢复
                if (availableAfter != POOL_SIZE) {
                    System.err.println(logWithTimestamp(">>> 警告：请求 " + i + " 后池状态未恢复！可用: " + availableAfter + "/" + POOL_SIZE));
                } else {
                    System.out.println(logWithTimestamp(">>> 验证通过：池状态已恢复 " + availableAfter + "/" + POOL_SIZE));
                }

            } catch (RuntimeException e) {
                exceptionInHandlerCount.incrementAndGet();
                results.add("HANDLER_EXCEPTION");
                int availableAfter = SerialListenerPools.BYTE_POOL.getAvailableCount();
                System.out.printf(logWithTimestamp("[请求 %2d/%2d] Handler异常 - 可用: %d/%d, 异常: %s%n"),
                    i, REQUEST_COUNT, availableAfter, POOL_SIZE, e.getMessage());

                // 验证池状态是否恢复（即使异常也应该恢复）
                if (availableAfter != POOL_SIZE) {
                    System.err.println(logWithTimestamp(">>> 失败：异常后池状态未恢复！可用: " + availableAfter + "/" + POOL_SIZE));
                } else {
                    System.out.println(logWithTimestamp(">>> 验证通过：异常后池状态仍恢复 " + availableAfter + "/" + POOL_SIZE));
                }

            } catch (Exception e) {
                results.add("ERROR: " + e.getClass().getSimpleName());
                System.err.println(logWithTimestamp("[请求 " + i + "] 异常: " + e.getMessage()));
            }

            totalRequests.incrementAndGet();

            // 等待间隔
            long elapsed = System.currentTimeMillis() - requestStartTime;
            long remainingWait = Math.max(0, REQUEST_INTERVAL_MS - elapsed);
            if (remainingWait > 0 && i < REQUEST_COUNT) {
                Thread.sleep(remainingWait);
            }
        }

        // 打印结果统计
        System.out.println();
        printSeparator('-', 70);
        System.out.println(logWithTimestamp("场景2结果统计:"));
        printSeparator('-', 70);

        System.out.println("请求序列:");
        for (int i = 0; i < REQUEST_COUNT; i++) {
            System.out.printf("  [%2d] 可用=%2d/%2d -> 结果=%s%n",
                i + 1, availableCounts.get(i), POOL_SIZE, results.get(i));
        }

        System.out.println();
        System.out.printf("总请求数: %d%n", REQUEST_COUNT);
        System.out.printf("Handler异常数: %d%n", exceptionInHandlerCount.get());
        System.out.printf("池耗尽次数: %d%n", poolExhaustionCount.get());
        System.out.println();

        printPoolStatus("场景2结束");

        // 判断测试是否通过
        if (poolExhaustionCount.get() > 0) {
            System.err.println(">>> 测试失败：检测到池耗尽！");
        } else if (exceptionInHandlerCount.get() == REQUEST_COUNT) {
            System.out.println(">>> 测试通过：所有请求的 Handler 都抛出了异常，但池状态正常！");
        } else {
            System.err.println(">>> 测试警告：Handler 异常数 (" + exceptionInHandlerCount.get() + ") 不等于请求数 (" + REQUEST_COUNT + ")");
        }
    }

    /**
     * 执行单次请求
     *
     * @return true=成功, false=超时
     */
    private static boolean executeRequest(SerialSource clientSource, int requestId, String scenario)
            throws Exception {

        String command = "REQ:" + scenario + ":" + requestId + "\r\n";

        // 创建 ByteResponseHandlerStrategy
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
            clientSource,
            ctx -> {
                // 处理响应
                byte[] responseBytes = ctx.getReceiveBytes();
                String response = new String(responseBytes);
                return response.contains("ACK");
            },
            bytes -> {
                // 检查响应完整性
                String response = new String(bytes);
                return response.contains("RESP") && response.contains("ACK") ? bytes : null;
            },
            ex -> {
                // 处理异常
                if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                    timeoutCount.incrementAndGet();
                    System.err.println(logWithTimestamp("[请求 " + requestId + "] 超时: " + ex.getMessage()));
                }
                return false;
            }
        );

        // 创建上下文
        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("req_" + requestId);

        // 使用 SerialTransactionStrategy 执行事务
        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
            clientSource,
            source -> source.asyncSendData(command.getBytes())
                .thenCompose(v -> strategy.handleResponse(context))
        );

        try {
            return result.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timeoutCount.incrementAndGet();
            throw e;
        }
    }

    /**
     * 执行单次请求（handleExceptionFunction 会抛异常）
     *
     * @return true=成功, false=失败
     * @throws RuntimeException handleExceptionFunction 故意抛出的异常
     */
    private static boolean executeRequestWithExceptionInHandler(SerialSource clientSource, int requestId, String scenario)
            throws Exception {

        String command = "REQ:" + scenario + ":" + requestId + "\r\n";

        // 创建 ByteResponseHandlerStrategy，handleExceptionFunction 会抛异常
        ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
            clientSource,
            ctx -> {
                // 处理响应
                byte[] responseBytes = ctx.getReceiveBytes();
                String response = new String(responseBytes);
                throw new RuntimeException("failured");
                // return response.contains("ACK");
            },
            bytes -> {
                // 检查响应完整性
                String response = new String(bytes);
                return response.contains("RESP") && response.contains("ACK") ? bytes : null;
            },
            ex -> {
                // ★ 故意抛出异常，测试 whenCompleteAsync 是否能确保 release
                System.err.println(logWithTimestamp("[请求 " + requestId + "] Handler 抛出异常"));
                throw new RuntimeException("TEST: Exception in handleExceptionFunction");
            }
        );

        // 创建上下文
        ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("req_" + requestId);

        // 使用 SerialTransactionStrategy 执行事务
        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
            clientSource,
            source -> source.asyncSendData(command.getBytes())
                .thenCompose(v -> strategy.handleResponse(context))
        );

        try {
            return result.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timeoutCount.incrementAndGet();
            throw e;
        } catch (RuntimeException e) {
            // 捕获 handleExceptionFunction 抛出的异常
            if (e.getMessage() != null && e.getMessage().contains("TEST: Exception in handleExceptionFunction")) {
                throw e;  // 重新抛出，让上层统计
            }
            throw e;
        }
    }

    /**
     * 设置 Server 端延迟响应器
     * 延迟 SERVER_DELAY_MS (600ms) 才响应，确保客户端超时
     * 使用同步延迟（阻塞当前线程），确保每个请求都超时
     */
    private static void setupDelayedServerResponder(SerialSource serverSource) {
        serverSource.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                try {
                    String received = new String(data, 0, length).trim();

                    if (received.startsWith("REQ:")) {
                        // 解析请求
                        String[] parts = received.split(":");
                        if (parts.length >= 3) {
                            String requestId = parts[2];

                            // 同步延迟响应（阻塞当前线程）
                            System.out.println(logWithTimestamp("[Server] 收到请求 " + requestId + "，延迟 " + SERVER_DELAY_MS + "ms 后响应"));
                            Thread.sleep(SERVER_DELAY_MS);

                            String response = "RESP:" + requestId + ":ACK\r\n";
                            serverSource.asyncSendData(response.getBytes()).get();
                            System.out.println(logWithTimestamp("[Server] 已发送响应 " + requestId));
                        }
                    }

                } catch (Exception e) {
                    System.err.println(logWithTimestamp("[Server] 处理异常: " + e.getMessage()));
                }
            }

            @Override
            public void onError(Exception ex) {
                System.err.println(logWithTimestamp("[Server] 错误: " + ex.getMessage()));
            }
        });
    }

    /**
     * 设置 Server 端正常响应器（无延迟）
     * 用于测试异常安全性
     */
    private static void setupNormalServerResponder(SerialSource serverSource) {
        serverSource.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                try {
                    String received = new String(data, 0, length).trim();

                    if (received.startsWith("REQ:")) {
                        // 解析请求
                        String[] parts = received.split(":");
                        if (parts.length >= 3) {
                            String requestId = parts[2];

                            // 立即响应（无延迟）
                            System.out.println(logWithTimestamp("[Server] 收到请求 " + requestId + "，立即响应"));
                            String response = "RESP:" + requestId + ":ACK\r\n";
                            serverSource.asyncSendData(response.getBytes()).get();
                            System.out.println(logWithTimestamp("[Server] 已发送响应 " + requestId));
                        }
                    }

                } catch (Exception e) {
                    System.err.println(logWithTimestamp("[Server] 处理异常: " + e.getMessage()));
                }
            }

            @Override
            public void onError(Exception ex) {
                System.err.println(logWithTimestamp("[Server] 错误: " + ex.getMessage()));
            }
        });
    }

    /**
     * 重置统计数据
     */
    private static void resetStatistics() {
        totalRequests.set(0);
        timeoutCount.set(0);
        poolExhaustionCount.set(0);
        tempListenerUsedCount.set(0);
        successCount.set(0);
    }

    /**
     * 打印最终报告
     */
    private static void printFinalReport() {
        System.out.println();
        System.out.println(logWithTimestamp("=== 最终测试报告 ==="));
        System.out.println();

        System.out.println("池状态:");
        System.out.println("  可用: " + SerialListenerPools.BYTE_POOL.getAvailableCount() + "/" + POOL_SIZE);
        System.out.println("  使用中: " + SerialListenerPools.BYTE_POOL.getInUseCount());
        System.out.println("  总计: " + SerialListenerPools.BYTE_POOL.getTotalCount());
        System.out.println();

        System.out.println("统计数据:");
        System.out.println("  总请求数: " + totalRequests.get());
        System.out.println("  成功: " + successCount.get());
        System.out.println("  超时: " + timeoutCount.get());
        System.out.println("  池耗尽次数: " + poolExhaustionCount.get());
        System.out.println("  临时监听器使用: " + tempListenerUsedCount.get());
        System.out.println();

        // 判断结论
        if (poolExhaustionCount.get() > 0 || tempListenerUsedCount.get() > 0) {
            System.err.println(">>> 结论：检测到 listener 池耗尽问题！");
            System.err.println("    请检查 ByteResponseHandlerStrategy 的超时处理逻辑");
        } else {
            System.out.println(">>> 结论：未检测到池耗尽问题");
        }
    }
}
