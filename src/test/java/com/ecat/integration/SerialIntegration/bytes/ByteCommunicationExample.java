package com.ecat.integration.SerialIntegration.bytes;

import com.ecat.integration.SerialIntegration.*;
import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Byte模式串口通信示例演示
 * 演示如何使用 ByteResponseHandlerStrategy 进行二进制数据串口通信
 *
 * 前提：手动执行以下命令创建虚拟串口
 * socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1
 *
 * @author coffee
 */
public class ByteCommunicationExample {

    // 使用现有的 SerialSource 类
    private SerialSource senderSerialSource;
    private SerialSource receiverSerialSource;

    // 使用现有的 SerialDataListener 接口的实现
    private final List<String> receivedMessages = new ArrayList<>();
    private CountDownLatch dataReceivedLatch = new CountDownLatch(1);

    // 锁管理
    private String senderKey;
    private String receiverKey;

    // 时间戳格式化器
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 获取带毫秒时间戳的日志消息
     */
    private static String logWithTimestamp(String message) {
        return "[" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "] " + message;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Byte模式串口通信示例演示 ===");
        System.out.println("注意：请确保已执行以下命令创建虚拟串口：");
        System.out.println("socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1");
        System.out.println();

        ByteCommunicationExample example = new ByteCommunicationExample();

        try {
            // 1. 初始化串口连接
            example.initializeSerialPorts();

            // 2. 演示基本通信
            example.demonstrateBasicCommunication();

            // 3. 演示中断驱动接收
            example.demonstrateInterruptDrivenReceiving();

            // 4. 演示智能模式
            example.demonstrateSmartMode();

            // 5. 演示双向通信
            example.demonstrateBidirectionalCommunication();

            // 6. 演示响应处理策略（多线程版本）
            example.demonstrateResponseHandlingMultithreaded();

            // 7. 演示响应处理策略
            example.demonstrateResponseHandling();

        } finally {
            // 清理资源
            example.cleanup();
        }
    }

    /**
     * 初始化串口连接
     */
    public void initializeSerialPorts() throws Exception {
        System.out.println("=== 初始化串口连接 ===");

        SerialInfo senderInfo = new SerialInfo("/dev/ttyV0", 9600, 8, 1, 0);
        SerialInfo receiverInfo = new SerialInfo("/dev/ttyV1", 9600, 8, 1, 0);

        senderSerialSource = new SerialSource(senderInfo);
        receiverSerialSource = new SerialSource(receiverInfo);

        senderSerialSource.registerIntegration("test-sender");
        receiverSerialSource.registerIntegration("test-receiver");

        if (!senderSerialSource.isPortOpen()) {
            throw new RuntimeException("发送方串口打开失败");
        }
        if (!receiverSerialSource.isPortOpen()) {
            throw new RuntimeException("接收方串口打开失败");
        }

        boolean isTestMode = senderSerialSource.isTestMode();
        System.out.println("串口初始化完成:");
        System.out.println("  发送方: /dev/ttyV0 - 端口打开: " + senderSerialSource.isPortOpen());
        System.out.println("  接收方: /dev/ttyV1 - 端口打开: " + receiverSerialSource.isPortOpen());
        System.out.println("  波特率: 9600");
        System.out.println("  运行模式: " + (isTestMode ? "测试模式（轮询）" : "生产模式（中断）"));
        System.out.println();
    }

    /**
     * 演示基本的串口通信
     */
    public void demonstrateBasicCommunication() throws Exception {
        System.out.println("=== 基本通信测试 ===");

        String message = "Hello Serial!";
        System.out.println("准备发送: " + message);

        CompletableFuture<Boolean> sendFuture =
            senderSerialSource.asyncSendData(message.getBytes());

        CompletableFuture<byte[]> receiveFuture =
            receiverSerialSource.asyncReadDataBytes();

        Boolean sent = sendFuture.get(3, TimeUnit.SECONDS);
        System.out.println("发送操作完成: " + sent);

        byte[] receivedBytes = receiveFuture.get(3, TimeUnit.SECONDS);
        String received = receivedBytes != null ? new String(receivedBytes) : null;
        System.out.println("接收到数据: [" + received + "]");

        System.out.println("发送成功: " + sent);
        System.out.println("接收到数据: " + (received != null ? received : "null"));
        System.out.println("基本通信测试成功！");
        System.out.println();
    }

    /**
     * 演示中断驱动的数据接收
     */
    public void demonstrateInterruptDrivenReceiving() throws Exception {
        System.out.println("=== 中断驱动接收测试 ===");

        SerialDataListener listener = null;
        try {
            boolean isTestMode = receiverSerialSource.isTestMode();
            System.out.println("当前模式: " + (isTestMode ? "测试模式（使用轮询）" : "生产模式（使用中断）"));

            listener = new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    String message = new String(data, 0, length);
                    synchronized (receivedMessages) {
                        receivedMessages.add(message);
                    }
                    System.out.println("✓ 中断驱动接收到: [" + message + "]");
                    dataReceivedLatch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("✗ 串口错误: " + ex.getMessage());
                    dataReceivedLatch.countDown();
                }
            };

            receiverSerialSource.addDataListener(listener);
            System.out.println("✓ 已注册中断数据监听器");

            synchronized (receivedMessages) {
                receivedMessages.clear();
            }
            dataReceivedLatch = new CountDownLatch(1);

            String testMessage = "Test Interrupt-Driven!";
            System.out.println("→ 发送测试数据: " + testMessage);

            CompletableFuture<Boolean> sendFuture = senderSerialSource.asyncSendData(testMessage.getBytes());
            Boolean sent = sendFuture.get(3, TimeUnit.SECONDS);
            System.out.println("发送完成: " + sent);

            System.out.println("等待中断驱动接收...");
            boolean received = dataReceivedLatch.await(3, TimeUnit.SECONDS);
            System.out.println("中断接收完成: " + (received ? "成功" : "超时"));

            synchronized (receivedMessages) {
                if (received && !receivedMessages.isEmpty()) {
                    System.out.println("✓ 中断驱动测试成功！");
                    System.out.println("  接收到的消息数量: " + receivedMessages.size());
                    for (String msg : receivedMessages) {
                        System.out.println("  - " + msg);
                    }
                } else {
                    System.out.println("✗ 中断驱动未接收到数据");
                }
            }
        } finally {
            if (listener != null) {
                receiverSerialSource.removeDataListener(listener);
                System.out.println("✓ 已清理中断驱动监听器");
            }
        }
        System.out.println();
    }

    /**
     * 演示智能模式自动切换
     * 展示 ByteResponseHandlerStrategy 的智能环境检测
     */
    public void demonstrateSmartMode() throws Exception {
        System.out.println("=== 智能模式测试 ===");

        SerialDataListener smartListener = null;
        try {
            boolean isTestMode = receiverSerialSource.isTestMode();

            System.out.println("当前运行模式: " +
                              (isTestMode ? "轮询模式（测试环境）" : "中断驱动模式（生产环境）"));

            smartListener = new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    String message = new String(data, 0, length);
                    System.out.println("✓ 智能模式监听器接收到: [" + message + "]");
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("智能模式监听器错误: " + ex.getMessage());
                }
            };

            receiverSerialSource.addDataListener(smartListener);
            System.out.println("✓ 已注册智能模式监听器");

            // 使用 ByteResponseHandlerStrategy
            ByteResponseHandlerStrategy<String> strategy =
                new ByteResponseHandlerStrategy<>(
                    receiverSerialSource,
                    ctx -> {
                        byte[] responseBytes = ctx.getReceiveBytes();
                        String response = new String(responseBytes);
                        System.out.println("智能处理响应: " + response);
                        return response.contains("SMART");
                    },
                    bytes -> {
                        String response = new String(bytes);
                        return response.contains("SMART") ? bytes : null;
                    },
                    ex -> {
                        System.err.println("智能模式处理异常: " + ex.getMessage());
                        return false;
                    }
                );

            String smartMessage = "SMART_MODE_TEST";
            senderSerialSource.asyncSendData(smartMessage.getBytes()).get();

            Thread.sleep(100);

            ByteResponseHandlingContext<String> context =
                new ByteResponseHandlingContext<>("smart_test");

            CompletableFuture<Boolean> result = strategy.handleResponse(context);
            Boolean handled = result.get(3, TimeUnit.SECONDS);

            System.out.println("智能响应处理完成: " + handled);
            System.out.println("模式检测工作正常");
        } finally {
            if (smartListener != null) {
                receiverSerialSource.removeDataListener(smartListener);
                System.out.println("✓ 已清理智能模式监听器");
            }
        }
        System.out.println();
    }

    /**
     * 演示双向通信
     */
    public void demonstrateBidirectionalCommunication() throws Exception {
        System.out.println("=== 双向通信测试 ===");

        List<String> aToBMessages = Collections.synchronizedList(new ArrayList<>());
        List<String> bToAMessages = Collections.synchronizedList(new ArrayList<>());

        SerialDataListener listenerA = new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                String msg = new String(data, 0, length);
                aToBMessages.add(msg);
                System.out.println("B -> A: " + msg);
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("A 端错误: " + ex.getMessage());
            }
        };

        SerialDataListener listenerB = new SerialDataListener() {
            @Override
            public void onDataReceived(byte[] data, int length) {
                String msg = new String(data, 0, length);
                bToAMessages.add(msg);
                System.out.println("A -> B: " + msg);
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("B 端错误: " + ex.getMessage());
            }
        };

        receiverSerialSource.addDataListener(listenerA);
        senderSerialSource.addDataListener(listenerB);

        String messageA = "Message from A to B";
        String messageB = "Reply from B to A";

        System.out.println("A 发送: " + messageA);
        CompletableFuture<Boolean> sendAFuture =
            senderSerialSource.asyncSendData(messageA.getBytes());

        Thread.sleep(200);

        System.out.println("B 发送: " + messageB);
        CompletableFuture<Boolean> sendBFuture =
            receiverSerialSource.asyncSendData(messageB.getBytes());

        sendAFuture.get();
        sendBFuture.get();

        Thread.sleep(500);

        boolean aReceivedB = !aToBMessages.isEmpty() &&
                            aToBMessages.get(0).contains(messageA);
        boolean bReceivedA = !bToAMessages.isEmpty() &&
                            bToAMessages.get(0).contains(messageB);

        System.out.println("A 收到 B 的消息: " + aReceivedB);
        System.out.println("B 收到 A 的消息: " + bReceivedA);

        receiverSerialSource.removeDataListener(listenerA);
        senderSerialSource.removeDataListener(listenerB);

        System.out.println("双向通信测试完成！");
        System.out.println();
    }

    /**
     * 演示响应处理策略
     */
    public void demonstrateResponseHandling() throws Exception {
        System.out.println("=== 响应处理策略演示 ===");

        SerialDataListener receiverListener = null;
        try {
            receiverListener = new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    String receivedCmd = new String(data, 0, length).trim();
                    System.out.println("✓ 接收方监听到命令: [" + receivedCmd + "]");

                    String response = "";
                    if (receivedCmd.contains("QUERY_STATUS")) {
                        response = "STATUS:OK";
                        System.out.println("→ 根据命令生成响应: " + response);
                    } else if (receivedCmd.contains("RESET")) {
                        response = "STATUS:RESET_OK";
                        System.out.println("→ 根据命令生成响应: " + response);
                    } else {
                        response = "STATUS:UNKNOWN_COMMAND";
                        System.out.println("→ 未知命令，生成错误响应: " + response);
                    }

                    response += "\r\n";

                    try {
                        receiverSerialSource.asyncSendData(response.getBytes()).get();
                        System.out.println("✓ 响应已发送回发送方");
                    } catch (Exception e) {
                        System.err.println("✗ 发送响应失败: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("接收方监听器错误: " + ex.getMessage());
                }
            };

            receiverSerialSource.addDataListener(receiverListener);
            System.out.println("✓ 已注册接收方监听器");

            System.out.println("\n=== 使用Byte模式事务策略发送命令 ===");

            if (senderKey != null) {
                senderSerialSource.release(senderKey);
                senderKey = null;
                System.out.println("✓ 释放发送方锁，准备使用事务策略");
            }

            CompletableFuture<Boolean> transactionResult = SerialTransactionStrategy.executeWithLambda(
                senderSerialSource,
                source -> {
                    ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("status_response");

                    ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                        source,
                        ctx -> {
                            byte[] responseBytes = ctx.getReceiveBytes();
                            String response = new String(responseBytes);
                            System.out.println("✓ 事务策略接收到响应: [" + response.trim() + "]");

                            if (response.contains("STATUS:OK")) {
                                System.out.println("  响应验证: 成功");
                                return true;
                            } else if (response.contains("STATUS:RESET_OK")) {
                                System.out.println("  响应验证: 重置成功");
                                return true;
                            } else if (response.contains("STATUS:UNKNOWN_COMMAND")) {
                                System.out.println("  响应验证: 命令未识别");
                                return false;
                            } else {
                                System.out.println("  响应验证: 响应格式错误，实际内容: [" + response + "]");
                                return false;
                            }
                        },
                        bytes -> {
                            String response = new String(bytes);
                            if (response.contains("STATUS:")) {
                                return bytes;
                            }
                            return null;
                        },
                        ex -> {
                            System.err.println("响应处理异常: " + ex.getMessage());
                            return false;
                        }
                    );

                    return source.asyncSendData("QUERY_STATUS\r\n".getBytes())
                        .thenCompose(v -> strategy.handleResponse(context));
                }
            );

            Boolean success = transactionResult.get(5, TimeUnit.SECONDS);
            System.out.println("\n事务执行结果: " + (success ? "成功" : "失败"));

            senderKey = senderSerialSource.acquire(5, TimeUnit.SECONDS);
            System.out.println("✓ 重新获取发送方锁: " + senderKey);

            System.out.println("\n=== 测试另一个命令 ===");

            senderSerialSource.release(senderKey);
            senderKey = null;

            CompletableFuture<Boolean> resetResult = SerialTransactionStrategy.executeWithLambda(
                senderSerialSource,
                source -> {
                    ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("reset_response");

                    ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                        source,
                        ctx -> {
                            byte[] responseBytes = ctx.getReceiveBytes();
                            String response = new String(responseBytes);
                            System.out.println("✓ 事务策略接收到响应: [" + response.trim() + "]");
                            return response.contains("STATUS:RESET_OK");
                        },
                        bytes -> {
                            String response = new String(bytes);
                            return response.contains("STATUS:") ? bytes : null;
                        },
                        ex -> {
                            System.err.println("响应处理异常: " + ex.getMessage());
                            return false;
                        }
                    );

                    return source.asyncSendData("RESET\r\n".getBytes())
                        .thenCompose(v -> strategy.handleResponse(context));
                }
            );

            Boolean resetSuccess = resetResult.get(5, TimeUnit.SECONDS);
            System.out.println("重置命令执行结果: " + (resetSuccess ? "成功" : "失败"));

            senderKey = senderSerialSource.acquire(5, TimeUnit.SECONDS);

            System.out.println("\n智能模式检测: " +
                              (receiverSerialSource.isTestMode() ?
                               "轮询模式" : "中断驱动模式"));

        } finally {
            if (receiverListener != null) {
                receiverSerialSource.removeDataListener(receiverListener);
                System.out.println("\n✓ 已清理接收方监听器");
            }
        }

        System.out.println("\n响应处理策略演示完成！");
        System.out.println();
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        System.out.println("=== 清理资源 ===");

        try {
            if (receiverSerialSource != null) {
                receiverSerialSource.removeAllDataListeners();
                System.out.println("已移除接收方所有监听器");
            }

            if (senderSerialSource != null) {
                senderSerialSource.removeAllDataListeners();
                System.out.println("已移除发送方所有监听器");
            }

            if (senderSerialSource != null && senderKey != null) {
                boolean released = senderSerialSource.release(senderKey);
                System.out.println("发送方锁释放: " + released);
            }

            if (receiverSerialSource != null && receiverKey != null) {
                boolean released = receiverSerialSource.release(receiverKey);
                System.out.println("接收方锁释放: " + released);
            }

            if (senderSerialSource != null) {
                senderSerialSource.closePort("test-sender");
                System.out.println("已关闭发送方串口");
            }

            if (receiverSerialSource != null) {
                receiverSerialSource.closePort("test-receiver");
                System.out.println("已关闭接收方串口");
            }

        } catch (Exception e) {
            System.err.println("清理资源时发生异常: " + e.getMessage());
        }

        System.out.println("资源清理完成");
        System.out.println();
        System.out.println("=== Byte模式串口通信示例演示完成 ===");
    }

    /**
     * 多线程版本的响应处理策略演示
     */
    public void demonstrateResponseHandlingMultithreaded() throws Exception {
        System.out.println(logWithTimestamp("=== 多线程响应处理策略演示 ==="));

        CountDownLatch serverStarted = new CountDownLatch(1);
        CountDownLatch transactionComplete = new CountDownLatch(1);
        AtomicBoolean serverSuccess = new AtomicBoolean(false);
        AtomicBoolean senderSuccess = new AtomicBoolean(false);

        final Exception[] serverException = new Exception[1];
        final Exception[] senderException = new Exception[1];

        Thread serverThread = null;
        Thread senderThread = null;

        try {
            serverThread = new Thread(() -> {
                try {
                    System.out.println(logWithTimestamp("[Server] 线程启动，等待监听器注册..."));

                    SerialDataListener receiverListener = new SerialDataListener() {
                        @Override
                        public void onDataReceived(byte[] data, int length) {
                            try {
                                String receivedCmd = new String(data, 0, length).trim();
                                System.out.println(logWithTimestamp("[Server] 接收到命令: [" + receivedCmd + "]"));

                                String response = "";
                                if (receivedCmd.contains("QUERY_STATUS")) {
                                    response = "STATUS:OK\r\n";
                                    System.out.println(logWithTimestamp("[Server] 生成响应: " + response.trim()));
                                } else if (receivedCmd.contains("RESET")) {
                                    response = "STATUS:RESET_OK\r\n";
                                    System.out.println(logWithTimestamp("[Server] 生成响应: " + response.trim()));
                                } else {
                                    response = "STATUS:UNKNOWN_COMMAND\r\n";
                                    System.out.println(logWithTimestamp("[Server] 未知命令，生成错误响应"));
                                }

                                System.out.println(logWithTimestamp("[Server] 发送响应..."));
                                Boolean sendResult = receiverSerialSource.asyncSendData(response.getBytes()).get(30, TimeUnit.SECONDS);

                                if (sendResult) {
                                    System.out.println(logWithTimestamp("[Server] ✓ 响应发送成功"));
                                    serverSuccess.set(true);
                                } else {
                                    System.out.println(logWithTimestamp("[Server] ✗ 响应发送失败"));
                                }

                            } catch (Exception e) {
                                System.err.println(logWithTimestamp("[Server] 处理命令时出错: " + e.getStackTrace().toString()));
                                serverSuccess.set(false);
                            }
                        }

                        @Override
                        public void onError(Exception ex) {
                            System.err.println(logWithTimestamp("[Server] 监听器错误: " + ex.getMessage()));
                            serverSuccess.set(false);
                        }
                    };

                    receiverSerialSource.addDataListener(receiverListener);
                    System.out.println(logWithTimestamp("[Server] ✓ 监听器已注册"));

                    serverStarted.countDown();
                    System.out.println(logWithTimestamp("[Server] 等待sender线程发送命令..."));

                    transactionComplete.await(15, TimeUnit.SECONDS);
                    System.out.println(logWithTimestamp("[Server] 事务完成，server线程退出"));

                } catch (Exception e) {
                    serverException[0] = e;
                    System.err.println(logWithTimestamp("[Server] 线程异常: " + e.getMessage()));
                }
            }, "Server-Thread");

            senderThread = new Thread(() -> {
                try {
                    if (!serverStarted.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("等待server启动超时");
                    }

                    System.out.println(logWithTimestamp("[Sender] 开始执行事务..."));

                    CompletableFuture<Boolean> transactionResult = SerialTransactionStrategy.executeWithLambda(
                        senderSerialSource,
                        source -> {
                            try {
                                ByteResponseHandlingContext<String> context = new ByteResponseHandlingContext<>("status_response");

                                ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                                    source,
                                    ctx -> {
                                        byte[] responseBytes = ctx.getReceiveBytes();
                                        String response = new String(responseBytes);
                                        System.out.println(logWithTimestamp("[Sender] 接收到响应: [" + response.trim() + "]"));

                                        if (response.contains("STATUS:OK")) {
                                            System.out.println(logWithTimestamp("[Sender] 响应验证: 成功"));
                                            return true;
                                        } else if (response.contains("STATUS:RESET_OK")) {
                                            System.out.println(logWithTimestamp("[Sender] 响应验证: 重置成功"));
                                            return true;
                                        } else {
                                            System.out.println(logWithTimestamp("[Sender] 响应验证: 失败 - " + response.trim()));
                                            return false;
                                        }
                                    },
                                    bytes -> {
                                        String response = new String(bytes);
                                        return response.contains("STATUS:") ? bytes : null;
                                    },
                                    ex -> {
                                        System.err.println("[Sender] 响应处理异常: " + ex.getMessage());
                                        return false;
                                    }
                                );

                                System.out.println(logWithTimestamp("[Sender] 发送命令: QUERY_STATUS"));
                                return source.asyncSendData("QUERY_STATUS\r\n".getBytes())
                                    .thenCompose(v -> strategy.handleResponse(context));

                            } catch (Exception e) {
                                System.err.println(logWithTimestamp("[Sender] 事务执行异常: " + e.getMessage()));
                                CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
                                failedFuture.completeExceptionally(e);
                                return failedFuture;
                            }
                        }
                    );

                    Boolean result = transactionResult.get(10, TimeUnit.SECONDS);
                    senderSuccess.set(result);

                    System.out.println(logWithTimestamp("[Sender] 事务执行结果: " + (result ? "成功" : "失败")));
                    System.out.println(logWithTimestamp("[Sender] 等待server完成响应处理..."));

                    Thread.sleep(1000);

                } catch (Exception e) {
                    senderException[0] = e;
                    System.err.println(logWithTimestamp("[Sender] 线程异常: " + e.getMessage()));
                } finally {
                    transactionComplete.countDown();
                }
            }, "Sender-Thread");

            serverThread.start();
            Thread.sleep(100);
            senderThread.start();

            serverThread.join(20000);
            senderThread.join(20000);

            if (serverException[0] != null) {
                throw new RuntimeException("Server线程异常", serverException[0]);
            }
            if (senderException[0] != null) {
                throw new RuntimeException("Sender线程异常", senderException[0]);
            }

            System.out.println("\n" + logWithTimestamp("=== 多线程执行结果 ==="));
            System.out.println(logWithTimestamp("Server成功: " + serverSuccess.get()));
            System.out.println(logWithTimestamp("Sender成功: " + senderSuccess.get()));
            System.out.println(logWithTimestamp("整体结果: " + (serverSuccess.get() && senderSuccess.get() ? "成功" : "失败")));

        } finally {
            receiverSerialSource.removeAllDataListeners();
            System.out.println(logWithTimestamp("✓ 已清理监听器"));
        }

        System.out.println("\n" + logWithTimestamp("多线程响应处理策略演示完成！"));
        System.out.println();
    }
}
