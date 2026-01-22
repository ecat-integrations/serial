package com.ecat.integration.SerialIntegration.bytes;

import com.ecat.integration.SerialIntegration.*;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.Listener.SerialListenerPools;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SendReadStrategy.SerialTimeoutScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Byte模式多端口并发性能测试
 * 测试20个串口同时访问时的线程池使用情况
 *
 * 1. 命令和响应格式增强
 *    - 命令：CMD:{发送端}:{接收端}:{操作索引}:{唯一ID}
 *    - 响应：RESP:{接收端}:{发送端}:{操作索引}:{唯一ID}:{数据}
 *  2. 消息隔离验证机制
 *    - 在 performSerialOperation 中验证响应来源
 *    - 确保每个端口只接收来自配对端口的响应
 *  3. 统计和报告功能
 *    - 在 printTestResults 中添加了隔离验证结果
 *    - 提供每个端口对和全局的隔离统计
 *
 * @author coffee
 */
public class MultiPortConcurrencyByteTest {

    private static final int PORT_COUNT = 20;
    private static final int OPERATIONS_PER_PORT = 300;

    // 统计数据
    private static final AtomicInteger totalOperations = new AtomicInteger(0);
    private static final AtomicInteger successfulOperations = new AtomicInteger(0);
    private static final AtomicInteger failedOperations = new AtomicInteger(0);
    private static final AtomicInteger poolExhaustionCount = new AtomicInteger(0);
    private static final AtomicInteger tempListenerUsedCount = new AtomicInteger(0);
    private static final AtomicLong maxConcurrentThreads = new AtomicLong(0);
    private static final AtomicInteger currentActiveThreads = new AtomicInteger(0);
    private static volatile boolean testRunning = true;
    private static long startTime;

    // 线程池统计
    private static final AtomicInteger maxPoolSizeReached = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("=== Byte模式多端口并发性能测试 ===");
        System.out.println("测试配置:");
        System.out.println("  - 串口数量: " + PORT_COUNT);
        System.out.println("  - 每个串口操作次数: " + OPERATIONS_PER_PORT);
        System.out.println("  - 总操作次数: " + (PORT_COUNT * OPERATIONS_PER_PORT));
        System.out.println();

        startTime = System.currentTimeMillis();

        // 1. 初始化20对串口
        List<SerialPortPair> portPairs = initializePorts();

        // 2. 记录初始线程池状态
        System.out.println("初始线程池状态:");
        System.out.println("  活跃线程池数: " + SerialTimeoutScheduler.getActiveSchedulerCount());
        System.out.println("  当前线程可用监听器: " + SerialListenerPools.BYTE_POOL.getAvailableCount());
        System.out.println();

        // 3. 执行并发测试
        ExecutorService executor = Executors.newFixedThreadPool(PORT_COUNT);

        for (int round = 0; round < OPERATIONS_PER_PORT && testRunning; round++) {
            final int currentRound = round;
            final CountDownLatch roundLatch = new CountDownLatch(PORT_COUNT);

            System.out.printf("\n---------------------------------执行第 %d 轮操作...\n", round + 1);

            for (SerialPortPair pair : portPairs) {
                executor.submit(() -> {
                    try {
                        currentActiveThreads.incrementAndGet();
                        updateMaxConcurrentThreads();
                        performSerialOperation(pair, currentRound);
                    } finally {
                        roundLatch.countDown();
                        currentActiveThreads.decrementAndGet();
                    }
                });
            }

            try {
                roundLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (testRunning) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 输出测试结果
        printTestResults(portPairs);

        // 5. 清理资源
        cleanupPorts(portPairs);
        executor.shutdown();

        System.out.println("\n测试完成！");
    }

    private static List<SerialPortPair> initializePorts() throws Exception {
        List<SerialPortPair> pairs = new ArrayList<>();

        for (int i = 0; i < PORT_COUNT; i++) {
            int portNum = i * 2;
            String senderPort = "/dev/ttyV" + portNum;
            String receiverPort = "/dev/ttyV" + (portNum + 1);

            SerialInfo senderInfo = new SerialInfo(senderPort, 9600, 8, 1, 0);
            SerialInfo receiverInfo = new SerialInfo(receiverPort, 9600, 8, 1, 0);

            SerialSource sender = new SerialSource(senderInfo);
            SerialSource receiver = new SerialSource(receiverInfo);

            pairs.add(new SerialPortPair(sender, receiver, i, senderPort, receiverPort));

            setupAutoResponder(receiver, i, receiverPort, senderPort);
        }

        Thread.sleep(500);

        return pairs;
    }

    private static void setupAutoResponder(SerialSource receiver, int portIndex,
                                         String receiverPortName, String senderPortName) {
        receiver.addDataListener(new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                try {
                    String received = new String(data, 0, length).trim();
                    if (received.startsWith("CMD:")) {
                        String[] parts = received.split(":");
                        if (parts.length < 5) {
                            throw new RuntimeException("Invalid command format: " + received);
                        }

                        String cmdReceiver = parts[2];
                        if (!receiverPortName.equals(cmdReceiver)) {
                            throw new RuntimeException(String.format(
                                "Command not for this port! Expected: %s, Got: %s, Command: %s",
                                receiverPortName, cmdReceiver, received));
                        }

                        String uniqueId = parts[4];

                        String response = String.format(
                            "RESP:%s:%s:%s:%s:ACK\r\n",
                            receiverPortName, senderPortName,
                            parts[3], uniqueId);

                        receiver.asyncSendData(response.getBytes());
                        System.out.printf("[%s] -> [%s]: %s",
                            receiverPortName, senderPortName, response);
                    }
                } catch (Exception e) {
                    System.err.printf("[%s] ERROR: %s%n", receiverPortName, e.getMessage());
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onError(Exception ex) {
                System.err.printf("[%s] Serial error: %s%n", receiverPortName, ex.getMessage());
            }
        });
    }

    private static boolean performSerialOperation(SerialPortPair pair, int operationIndex) {
        int availableBefore = SerialListenerPools.BYTE_POOL.getAvailableCount();
        boolean poolExhausted = false;

        try {
            totalOperations.incrementAndGet();

            long uniqueId = System.nanoTime() + operationIndex * 1000000 + pair.index * 100000000;

            // 使用 ByteResponseHandlerStrategy
            ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                pair.sender,
                ctx -> {
                    byte[] responseBytes = ctx.getReceiveBytes();
                    String response = new String(responseBytes);

                    boolean isValid = false;
                    if (response.startsWith("RESP:")) {
                        String[] parts = response.split(":");
                        if (parts.length >= 5) {
                            String respReceiver = parts[1];
                            String respSender = parts[2];

                            isValid = pair.senderPortName.equals(respSender) &&
                                     pair.receiverPortName.equals(respReceiver);
                        }
                    }

                    pair.receivedMessages.incrementAndGet();
                    if (isValid) {
                        pair.validMessages.incrementAndGet();
                        System.out.printf("[%s] ✓ 收到正确响应: %s",
                            pair.senderPortName, response);
                    } else {
                        pair.invalidMessages.incrementAndGet();
                        System.out.printf("[%s] ✗ 收到错误响应: %s",
                            pair.senderPortName, response);
                    }

                    return isValid;
                },
                bytes -> {
                    String response = new String(bytes);
                    return response.contains("RESP") && response.endsWith("\r\n") ? bytes : null;
                },
                ex -> false
            );

            // 创建 Byte 响应上下文
            ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("op_" + operationIndex);

            String command = String.format("CMD:%s:%s:%d:%d\r\n",
                pair.senderPortName, pair.receiverPortName,
                operationIndex, uniqueId);

            CompletableFuture<Boolean> transactionResult = SerialTransactionStrategy.executeWithLambda(
                pair.sender,
                source -> source.asyncSendData(command.getBytes())
                    .thenCompose(v -> strategy.handleResponse(context))
            );

            Boolean success = transactionResult.get(2, TimeUnit.SECONDS);

            int totalAfter = SerialListenerPools.BYTE_POOL.getTotalCount();

            if (availableBefore == 0) {
                poolExhausted = true;
                poolExhaustionCount.incrementAndGet();
                tempListenerUsedCount.incrementAndGet();
            }

            if (success != null && success) {
                successfulOperations.incrementAndGet();
            } else {
                failedOperations.incrementAndGet();
            }

            if (totalAfter > maxPoolSizeReached.get()) {
                maxPoolSizeReached.set(totalAfter);
            }

            if (poolExhausted) {
                System.out.printf("DEBUG: Port %d 操作 %d - 池耗尽，使用临时监听器%n",
                    pair.index, operationIndex);
            }

            return success != null && success;

        } catch (Exception e) {
            System.out.printf("ERROR: %s%n", e);
            failedOperations.incrementAndGet();
            if (availableBefore == 0) {
                poolExhaustionCount.incrementAndGet();
                tempListenerUsedCount.incrementAndGet();
            }
            return false;
        }
    }

    private static void updateMaxConcurrentThreads() {
        int current = currentActiveThreads.get();
        long max = maxConcurrentThreads.get();
        while (current > max && !maxConcurrentThreads.compareAndSet(max, current)) {
            max = maxConcurrentThreads.get();
        }
    }

    private static void printTestResults(List<SerialPortPair> portPairs) {
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("\n=== 消息隔离验证结果 ===");
        System.out.println("端口消息隔离统计:");

        int totalReceived = 0;
        int totalValid = 0;
        int totalInvalid = 0;

        for (SerialPortPair pair : portPairs) {
            int received = pair.receivedMessages.get();
            int valid = pair.validMessages.get();
            int invalid = pair.invalidMessages.get();

            totalReceived += received;
            totalValid += valid;
            totalInvalid += invalid;

            double isolationRate = received > 0 ? (valid * 100.0 / received) : 0;

            System.out.printf("  端口对 %2d (%s->%s): 接收=%5d, 有效=%5d, 无效=%5d, 隔离率=%5.1f%%%n",
                pair.index,
                pair.senderPortName.substring(pair.senderPortName.length() - 2),
                pair.receiverPortName.substring(pair.receiverPortName.length() - 2),
                received, valid, invalid, isolationRate);
        }

        System.out.println("\n全局隔离统计:");
        System.out.printf("  总接收消息: %d%n", totalReceived);
        System.out.printf("  有效消息: %d%n", totalValid);
        System.out.printf("  隔离违规: %d%n", totalInvalid);
        System.out.printf("  整体隔离率: %.2f%%%n",
            totalReceived > 0 ? (totalValid * 100.0 / totalReceived) : 0);

        if (totalInvalid > 0) {
            System.out.println("\n⚠ 检测到端口隔离违规！");
            System.out.printf("  违规率: %.2f%%%n", (totalInvalid * 100.0 / totalReceived));
            System.out.println("  这可能表明存在线程安全问题或监听器池冲突");
        } else {
            System.out.println("\n✓ 所有消息隔离验证通过");
        }

        System.out.println("\n=== 测试结果统计 ===");
        System.out.printf("测试时长: %.2f 秒%n", duration / 1000.0);

        int total = totalOperations.get();
        int success = successfulOperations.get();
        int failed = failedOperations.get();
        int exhausted = poolExhaustionCount.get();
        int tempUsed = tempListenerUsedCount.get();

        int expectedTotal = PORT_COUNT * OPERATIONS_PER_PORT;
        boolean isDataConsistent = (success + failed) == total && total == expectedTotal;

        System.out.printf("总操作数: %d (预期: %d)%n", total, expectedTotal);
        System.out.printf("成功操作数: %d%n", success);
        System.out.printf("失败操作数: %d%n", failed);
        System.out.printf("对象池耗尽次数: %d%n", exhausted);
        System.out.printf("使用临时监听器次数: %d%n", tempUsed);
        System.out.printf("成功率: %.2f%%%n", total > 0 ? (success * 100.0 / total) : 0);
        System.out.printf("失败率: %.2f%%%n", total > 0 ? (failed * 100.0 / total) : 0);
        System.out.printf("池耗尽率: %.2f%%%n", total > 0 ? (exhausted * 100.0 / total) : 0);
        System.out.printf("临时监听器使用率: %.2f%%%n", total > 0 ? (tempUsed * 100.0 / total) : 0);

        System.out.println("\n=== 数据一致性验证 ===");
        System.out.printf("统计数据一致性: %s%n", isDataConsistent ? "✓ 通过" : "✗ 失败");
        if (!isDataConsistent) {
            System.out.printf("  - 成功+失败 = %d (应等于总操作数 %d)%n", success + failed, total);
            System.out.printf("  - 总操作数 = %d (应等于预期 %d)%n", total, expectedTotal);
        }
        System.out.printf("池耗尽统计一致性: %s%n", exhausted == tempUsed ? "✓ 一致" : "✗ 不一致");

        System.out.println("\n=== 并发分析 ===");
        System.out.printf("最大并发线程数: %d%n", maxConcurrentThreads.get());
        System.out.printf("目标并发数: %d (串口数量)%n", PORT_COUNT);

        if (maxConcurrentThreads.get() == PORT_COUNT) {
            System.out.printf("✓ 完全并发：所有%d个串口同时工作%n", PORT_COUNT);
        } else if (maxConcurrentThreads.get() >= PORT_COUNT / 2) {
            System.out.printf("⚠ 部分并发：约 %.1f%% 的串口同时工作%n",
                (maxConcurrentThreads.get() * 100.0 / PORT_COUNT));
        } else {
            System.out.println("⚠ 低并发：串口主要是顺序执行");
        }

        System.out.println("\n=== 线程池使用统计 ===");
        System.out.println("活跃线程池数: " + SerialTimeoutScheduler.getActiveSchedulerCount());
        System.out.println("监听器池最终状态:");
        System.out.println("  可用监听器: " + SerialListenerPools.BYTE_POOL.getAvailableCount());
        System.out.println("  总监听器数: " + SerialListenerPools.BYTE_POOL.getTotalCount());
        System.out.println("  最大池大小达到: " + maxPoolSizeReached.get());

        double avgOpsPerSecond = total / (duration / 1000.0);
        System.out.printf("\n性能数据:%n");
        System.out.printf("  平均每秒操作数: %.2f ops/s%n", avgOpsPerSecond);
        System.out.printf("  平均每操作耗时: %.2f ms%n", duration / (double) total);
        System.out.printf("  每个串口平均速度: %.2f ops/s%n", avgOpsPerSecond / PORT_COUNT);

        System.out.println("\n=== 关键发现 ===");
        if (exhausted > 0) {
            System.out.println("⚠ 关键问题：对象池配置不足！");
            System.out.printf("  - 池耗尽次数: %d 次（%.2f%%）%n", exhausted, exhausted * 100.0 / total);
            System.out.printf("  - 每次池耗尽都创建临时监听器，影响性能%n");
            System.out.printf("  - 临时监听器使用率: %.2f%%%n", tempUsed * 100.0 / total);
        } else {
            System.out.println("✓ 对象池配置充足");
        }

        System.out.println("\n=== 优化建议 ===");
        if (exhausted > 0) {
            double poolUsageRate = (SerialListenerPools.BYTE_POOL.getTotalCount() -
                                   SerialListenerPools.BYTE_POOL.getAvailableCount()) * 100.0 /
                                   Math.max(1, SerialListenerPools.BYTE_POOL.getTotalCount());

            System.out.printf("1. 当前池利用率: %.1f%%%n", poolUsageRate);
            System.out.printf("2. 建议每线程池大小: %d 个监听器%n",
                Math.max(5, (int)(5 * (1 + exhausted * 1.0 / PORT_COUNT))));

            if (poolUsageRate > 80) {
                System.out.println("3. 池利用率过高，建议增加到 10-20 个");
            }
        } else {
            System.out.println("1. 对象池配置合理，无需调整");
            System.out.println("2. 可以考虑减少池大小以节省内存");
        }

        if (maxConcurrentThreads.get() == 1 && PORT_COUNT > 1) {
            System.out.println("⚠ 注意：检测到可能存在ThreadLocal共享问题");
            System.out.println("  建议检查ExecutorService的线程池配置");
        }

        if (avgOpsPerSecond > 1000) {
            System.out.println("✓ 性能优秀：> 1000 ops/s");
        } else if (avgOpsPerSecond > 500) {
            System.out.println("✓ 性能良好：> 500 ops/s");
        } else if (avgOpsPerSecond > 200) {
            System.out.println("⚠ 性能一般：< 500 ops/s");
        } else {
            System.out.println("❌ 性能较差：< 200 ops/s，需要优化");
        }
    }

    private static void cleanupPorts(List<SerialPortPair> portPairs) {
        System.out.println("\n清理串口资源...");
        for (SerialPortPair pair : portPairs) {
            try {
                pair.sender.close();
                pair.receiver.close();
                SerialTimeoutScheduler.cleanupScheduler(pair.sender.getPortName());
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
        SerialListenerPools.BYTE_POOL.cleanup();
        System.out.println("资源清理完成");
    }

    private static class SerialPortPair {
        final SerialSource sender;
        final SerialSource receiver;
        final int index;
        final String senderPortName;
        final String receiverPortName;

        final AtomicInteger receivedMessages = new AtomicInteger(0);
        final AtomicInteger validMessages = new AtomicInteger(0);
        final AtomicInteger invalidMessages = new AtomicInteger(0);

        SerialPortPair(SerialSource sender, SerialSource receiver, int index,
                        String senderPortName, String receiverPortName) {
            this.sender = sender;
            this.receiver = receiver;
            this.index = index;
            this.senderPortName = senderPortName;
            this.receiverPortName = receiverPortName;
        }
    }
}
