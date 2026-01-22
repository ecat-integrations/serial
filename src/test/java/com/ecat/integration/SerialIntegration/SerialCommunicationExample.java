package com.ecat.integration.SerialIntegration;

import com.ecat.integration.SerialIntegration.Listener.SerialDataListener;
import com.ecat.integration.SerialIntegration.SendReadStrategy.DefaultResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ResponseHandlingContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 串口通信示例演示
 * 演示如何使用现有的 SerialSource、SerialDataListener 和 DefaultResponseHandlerStrategy
 * 进行真实的串口通信（中断驱动模式）
 *
 * 前提：手动执行以下命令创建虚拟串口
 * socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1
 * 
 * @author coffee
 */
public class SerialCommunicationExample {

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
        System.out.println("=== Serial 串口通信示例演示 ===");
        System.out.println("注意：请确保已执行以下命令创建虚拟串口：");
        System.out.println("socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1");
        System.out.println();

        SerialCommunicationExample example = new SerialCommunicationExample();

        try {
            // 1. 初始化串口连接
            example.initializeSerialPorts();

            // 2. 演示基本通信（无事务不可用于ecat生产）
            example.demonstrateBasicCommunication();

            // 3. 演示中断驱动接收（无事务不可用于ecat生产）
            example.demonstrateInterruptDrivenReceiving();

            // 4. 演示智能模式（仅用于测试模式）
            example.demonstrateSmartMode();

            // 5. 演示双向通信（无事务不可用于ecat生产）
            example.demonstrateBidirectionalCommunication();

            // 6. 演示响应处理策略【生产用例】（使用多线程版本解决同步异步混写问题）
            example.demonstrateResponseHandlingMultithreaded();

            // 7. 演示响应处理策略（生产的另一个版本）
            example.demonstrateResponseHandling();

        } finally {
            // 7. 清理资源
            example.cleanup();
        }
    }

    /**
     * 初始化串口连接
     * 使用现有的 SerialInfo 和 SerialSource 类
     */
    public void initializeSerialPorts() throws Exception {
        System.out.println("=== 初始化串口连接 ===");

        // 使用现有的 SerialInfo 配置类
        SerialInfo senderInfo = new SerialInfo("/dev/ttyV0", 9600, 8, 1, 0);
        SerialInfo receiverInfo = new SerialInfo("/dev/ttyV1", 9600, 8, 1, 0);

        // 使用现有的 SerialSource 类创建串口连接
        senderSerialSource = new SerialSource(senderInfo);
        receiverSerialSource = new SerialSource(receiverInfo);

        // 注册身份以便正确关闭串口
        senderSerialSource.registerIntegration("test-sender");
        receiverSerialSource.registerIntegration("test-receiver");

        // 检查串口是否成功打开
        if (!senderSerialSource.isPortOpen()) {
            throw new RuntimeException("发送方串口打开失败");
        }
        if (!receiverSerialSource.isPortOpen()) {
            throw new RuntimeException("接收方串口打开失败");
        }

        // 使用现有的锁机制获取串口访问权
        // senderKey = senderSerialSource.acquire(5, TimeUnit.SECONDS);
        // receiverKey = receiverSerialSource.acquire(5, TimeUnit.SECONDS);

        // if (senderKey == null || receiverKey == null) {
        //     throw new RuntimeException("无法获取串口锁");
        // }

        // 检查运行模式
        boolean isTestMode = senderSerialSource.isTestMode();
        System.out.println("串口初始化完成:");
        System.out.println("  发送方: /dev/ttyV0 - 端口打开: " + senderSerialSource.isPortOpen());
        System.out.println("  接收方: /dev/ttyV1 - 端口打开: " + receiverSerialSource.isPortOpen());
        System.out.println("  波特率: 9600");
        // System.out.println("  锁获取成功: " + senderKey + ", " + receiverKey);
        System.out.println("  运行模式: " + (isTestMode ? "测试模式（轮询）" : "生产模式（中断）"));
        System.out.println();
    }

    /**
     * 演示基本的串口通信
     * 使用现有的 SerialSource 异步发送和接收方法
     */
    public void demonstrateBasicCommunication() throws Exception {
        System.out.println("=== 基本通信测试 ===");

        String message = "Hello Serial!";
        System.out.println("准备发送: " + message);

        // 使用现有的异步发送方法
        CompletableFuture<Boolean> sendFuture =
            senderSerialSource.asyncSendData(message.getBytes());

        // 使用现有的异步读取方法
        CompletableFuture<byte[]> receiveFuture =
            receiverSerialSource.asyncReadDataBytes();

        // 等待发送完成
        Boolean sent = sendFuture.get(3, TimeUnit.SECONDS);
        System.out.println("发送操作完成: " + sent);

        // 等待接收完成
        byte[] receivedBytes = receiveFuture.get(3, TimeUnit.SECONDS);
        String received = receivedBytes != null ? new String(receivedBytes) : null;
        System.out.println("接收到数据: [" + received + "]");

        // 验证结果
        System.out.println("发送成功: " + sent);
        System.out.println("接收到数据: " + (received != null ? received : "null"));
        System.out.println("基本通信测试成功！");
        System.out.println();
    }

    /**
     * 演示中断驱动的数据接收
     * 使用现有的 SerialDataListener 接口
     */
    public void demonstrateInterruptDrivenReceiving() throws Exception {
        System.out.println("=== 中断驱动接收测试 ===");

        SerialDataListener listener = null;
        try {
            // 检查运行模式
            boolean isTestMode = receiverSerialSource.isTestMode();
            System.out.println("当前模式: " + (isTestMode ? "测试模式（使用轮询）" : "生产模式（使用中断）"));

            // 使用现有的 SerialDataListener 接口创建监听器
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

            // 使用现有的 addDataListener 方法注册监听器
            receiverSerialSource.addDataListener(listener);
            System.out.println("✓ 已注册中断数据监听器");

            // 清空消息列表并重置倒计时
            synchronized (receivedMessages) {
                receivedMessages.clear();
            }
            dataReceivedLatch = new CountDownLatch(1);

            // 发送数据测试
            String testMessage = "Test Interrupt-Driven!";
            System.out.println("→ 发送测试数据: " + testMessage);

            // 异步发送数据
            CompletableFuture<Boolean> sendFuture = senderSerialSource.asyncSendData(testMessage.getBytes());
            Boolean sent = sendFuture.get(3, TimeUnit.SECONDS);
            System.out.println("发送完成: " + sent);

            // 等待中断触发
            System.out.println("等待中断驱动接收...");
            boolean received = dataReceivedLatch.await(3, TimeUnit.SECONDS);
            System.out.println("中断接收完成: " + (received ? "成功" : "超时"));

            // 验证结果
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
            // 清理监听器
            if (listener != null) {
                receiverSerialSource.removeDataListener(listener);
                System.out.println("✓ 已清理中断驱动监听器");
            }
        }
        System.out.println();
    }

    /**
     * 演示智能模式自动切换
     * 展示 DefaultResponseHandlerStrategy 的智能环境检测
     */
    public void demonstrateSmartMode() throws Exception {
        System.out.println("=== 智能模式测试 ===");

        // 添加一个临时的监听器来观察智能模式测试
        SerialDataListener smartListener = null;
        try {
            // 检测当前运行模式
            boolean isTestMode = receiverSerialSource.isTestMode();

            System.out.println("当前运行模式: " +
                              (isTestMode ? "轮询模式（测试环境）" : "中断驱动模式（生产环境）"));

            // 创建临时监听器来观察数据接收
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

            // 使用现有的 DefaultResponseHandlerStrategy
            DefaultResponseHandlerStrategy<String> strategy =
                new DefaultResponseHandlerStrategy<>(
                    receiverSerialSource,
                    ctx -> {
                        String response = ctx.getReceiveBuffer().toString();
                        System.out.println("智能处理响应: " + response);
                        return response.contains("SMART");
                    },
                    response -> response != null && response.contains("SMART") ? "MATCH" : null,
                    ex -> {
                        System.err.println("智能模式处理异常: " + ex.getMessage());
                        return false;
                    }
                );

            // 发送测试数据
            String smartMessage = "SMART_MODE_TEST";
            senderSerialSource.asyncSendData(smartMessage.getBytes()).get();

            // 等待数据传输
            Thread.sleep(100);

            // 使用现有的 ResponseHandlingContext
            ResponseHandlingContext<String> context =
                new ResponseHandlingContext<>("smart_test");

            // 执行智能响应处理
            CompletableFuture<Boolean> result = strategy.handleResponse(context);
            Boolean handled = result.get(3, TimeUnit.SECONDS);

            System.out.println("智能响应处理完成: " + handled);
            System.out.println("模式检测工作正常");
        } finally {
            // 清理监听器
            if (smartListener != null) {
                receiverSerialSource.removeDataListener(smartListener);
                System.out.println("✓ 已清理智能模式监听器");
            }
        }
        System.out.println();
    }

    /**
     * 演示双向通信
     * 两个串口同时发送和接收数据
     */
    public void demonstrateBidirectionalCommunication() throws Exception {
        System.out.println("=== 双向通信测试 ===");

        // 创建两个监听器
        List<String> aToBMessages = Collections.synchronizedList(new ArrayList<>());
        List<String> bToAMessages = Collections.synchronizedList(new ArrayList<>());

        // 为接收方 A 创建监听器
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

        // 为接收方 B 创建监听器
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

        // 注册监听器
        receiverSerialSource.addDataListener(listenerA);  // 监听从 A 到 B
        senderSerialSource.addDataListener(listenerB);   // 监听从 B 到 A

        // 发送测试消息
        String messageA = "Message from A to B";
        String messageB = "Reply from B to A";

        System.out.println("A 发送: " + messageA);
        CompletableFuture<Boolean> sendAFuture =
            senderSerialSource.asyncSendData(messageA.getBytes());

        // 等待一下让数据传输完成
        Thread.sleep(200);

        System.out.println("B 发送: " + messageB);
        CompletableFuture<Boolean> sendBFuture =
            receiverSerialSource.asyncSendData(messageB.getBytes());

        // 等待发送完成
        sendAFuture.get();
        sendBFuture.get();

        // 等待数据接收
        Thread.sleep(500);

        // 验证双向通信
        boolean aReceivedB = !aToBMessages.isEmpty() &&
                            aToBMessages.get(0).contains(messageA);
        boolean bReceivedA = !bToAMessages.isEmpty() &&
                            bToAMessages.get(0).contains(messageB);

        System.out.println("A 收到 B 的消息: " + aReceivedB);
        System.out.println("B 收到 A 的消息: " + bReceivedA);

        // 清理监听器
        receiverSerialSource.removeDataListener(listenerA);
        senderSerialSource.removeDataListener(listenerB);

        System.out.println("双向通信测试完成！");
        System.out.println();
    }

    /**
     * 演示响应处理策略
     * receiverSerialSource 使用 SerialDataListener 监听，senderSerialSource 使用 SerialTransactionStrategy 发送并接收
     */
    public void demonstrateResponseHandling() throws Exception {
        System.out.println("=== 响应处理策略演示 ===");

        SerialDataListener receiverListener = null;
        try {
            // 1. 在接收方（receiverSerialSource）注册监听器
            receiverListener = new SerialDataListener() {
                @Override
                public void onDataReceived(byte[] data, int length) {
                    String receivedCmd = new String(data, 0, length).trim();
                    System.out.println("✓ 接收方监听到命令: [" + receivedCmd + "]");

                    // 根据收到的命令进行响应处理
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

                    // 添加换行符
                    response += "\r\n";

                    // 发送响应回发送方（使用 receiverSerialSource 的锁）
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

            // 2. 发送方使用 SerialTransactionStrategy 发送命令并处理响应
            System.out.println("\n=== 使用事务策略发送命令 ===");

            // 先释放发送方的锁，让 SerialTransactionStrategy 来管理
            if (senderKey != null) {
                senderSerialSource.release(senderKey);
                senderKey = null;
                System.out.println("✓ 释放发送方锁，准备使用事务策略");
            }

            // 使用 SerialTransactionStrategy 执行事务
            CompletableFuture<Boolean> transactionResult = SerialTransactionStrategy.executeWithLambda(
                senderSerialSource,
                source -> {
                    // 创建响应处理上下文
                    ResponseHandlingContext<String> context = new ResponseHandlingContext<>("status_response");

                    // 创建响应处理策略
                    DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                        source,
                        ctx -> {
                            // 处理收到的响应
                            String response = ctx.getReceiveBuffer().toString();
                            System.out.println("✓ 事务策略接收到响应: [" + response.trim() + "]");

                            // 验证响应内容
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
                        response -> {
                            // 检查响应完整性
                            if (response != null && response.contains("STATUS:")) {
                                return response;
                            }
                            return null; // 响应不完整
                        },
                        ex -> {
                            // 处理异常
                            System.err.println("响应处理异常: " + ex.getMessage());
                            return false;
                        }
                    );

                    // 发送命令并处理响应
                    return source.asyncSendData("QUERY_STATUS\r\n".getBytes())
                        .thenCompose(v -> strategy.handleResponse(context));
                }
            );

            // 等待事务完成
            Boolean success = transactionResult.get(5, TimeUnit.SECONDS);
            System.out.println("\n事务执行结果: " + (success ? "成功" : "失败"));

            // 重新获取发送方锁
            senderKey = senderSerialSource.acquire(5, TimeUnit.SECONDS);
            System.out.println("✓ 重新获取发送方锁: " + senderKey);

            // 3. 测试另一个命令
            System.out.println("\n=== 测试另一个命令 ===");

            // 再次释放锁
            senderSerialSource.release(senderKey);
            senderKey = null;

            CompletableFuture<Boolean> resetResult = SerialTransactionStrategy.executeWithLambda(
                senderSerialSource,
                source -> {
                    ResponseHandlingContext<String> context = new ResponseHandlingContext<>("reset_response");

                    DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                        source,
                        ctx -> {
                            String response = ctx.getReceiveBuffer().toString();
                            System.out.println("✓ 事务策略接收到响应: [" + response.trim() + "]");
                            return response.contains("STATUS:RESET_OK");
                        },
                        response -> response != null && response.contains("STATUS:") ? response : null,
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

            // 重新获取发送方锁
            senderKey = senderSerialSource.acquire(5, TimeUnit.SECONDS);

            System.out.println("\n智能模式检测: " +
                              (receiverSerialSource.isTestMode() ?
                               "轮询模式" : "中断驱动模式"));

        } finally {
            // 清理接收方监听器
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
     * 释放锁和清理监听器
     */
    public void cleanup() {
        System.out.println("=== 清理资源 ===");

        try {
            // 移除所有监听器
            if (receiverSerialSource != null) {
                receiverSerialSource.removeAllDataListeners();
                System.out.println("已移除接收方所有监听器");
            }

            if (senderSerialSource != null) {
                senderSerialSource.removeAllDataListeners();
                System.out.println("已移除发送方所有监听器");
            }

            // 释放锁
            if (senderSerialSource != null && senderKey != null) {
                boolean released = senderSerialSource.release(senderKey);
                System.out.println("发送方锁释放: " + released);
            }

            if (receiverSerialSource != null && receiverKey != null) {
                boolean released = receiverSerialSource.release(receiverKey);
                System.out.println("接收方锁释放: " + released);
            }

            // 关闭串口
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
        System.out.println("=== Serial 串口通信示例演示完成 ===");
    }

    /**
     * 多线程版本的响应处理策略演示
     * 使用独立线程处理server和sender，彻底解决同步异步混写问题
     */
    public void demonstrateResponseHandlingMultithreaded() throws Exception {
        System.out.println(logWithTimestamp("=== 多线程响应处理策略演示 ==="));

        // 线程同步工具
        CountDownLatch serverStarted = new CountDownLatch(1);  // 通知server已启动
        CountDownLatch transactionComplete = new CountDownLatch(1);  // 通知事务完成
        AtomicBoolean serverSuccess = new AtomicBoolean(false);
        AtomicBoolean senderSuccess = new AtomicBoolean(false);

        final Exception[] serverException = new Exception[1];
        final Exception[] senderException = new Exception[1];

        Thread serverThread = null;
        Thread senderThread = null;

        try {
            // 1. 创建并启动Server线程（接收方）
            serverThread = new Thread(() -> {
                try {
                    System.out.println(logWithTimestamp("[Server] 线程启动，等待监听器注册..."));

                    // 注册监听器（server线程不需要锁）
                    SerialDataListener receiverListener = new SerialDataListener() {
                        @Override
                        public void onDataReceived(byte[] data, int length) {
                            try {
                                String receivedCmd = new String(data, 0, length).trim();
                                System.out.println(logWithTimestamp("[Server] 接收到命令: [" + receivedCmd + "]"));

                                // 生成响应
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

                                // 发送响应（server线程独立发送，无锁冲突）
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

                    // 通知主线程server已准备就绪
                    serverStarted.countDown();
                    System.out.println(logWithTimestamp("[Server] 等待sender线程发送命令..."));

                    // 等待事务完成
                    transactionComplete.await(15, TimeUnit.SECONDS);
                    System.out.println(logWithTimestamp("[Server] 事务完成，server线程退出"));

                } catch (Exception e) {
                    serverException[0] = e;
                    System.err.println(logWithTimestamp("[Server] 线程异常: " + e.getMessage()));
                }
            }, "Server-Thread");

            // 2. 创建Sender线程（发送方）
            senderThread = new Thread(() -> {
                try {
                    // 等待server启动完成
                    if (!serverStarted.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("等待server启动超时");
                    }

                    System.out.println(logWithTimestamp("[Sender] 开始执行事务..."));

                    // 使用SerialTransactionStrategy发送命令并接收响应
                    CompletableFuture<Boolean> transactionResult = SerialTransactionStrategy.executeWithLambda(
                        senderSerialSource,
                        source -> {
                            try {
                                // 创建响应处理上下文
                                ResponseHandlingContext<String> context = new ResponseHandlingContext<>("status_response");

                                // 创建响应处理策略
                                DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                                    source,
                                    ctx -> {
                                        String response = ctx.getReceiveBuffer().toString();
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
                                    response -> response != null && response.contains("STATUS:") ? response : null,
                                    ex -> {
                                        System.err.println("[Sender] 响应处理异常: " + ex.getMessage());
                                        return false;
                                    }
                                );

                                // 发送命令并处理响应
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

                    // 等待事务完成
                    Boolean result = transactionResult.get(10, TimeUnit.SECONDS);
                    senderSuccess.set(result);

                    System.out.println(logWithTimestamp("[Sender] 事务执行结果: " + (result ? "成功" : "失败")));
                    System.out.println(logWithTimestamp("[Sender] 等待server完成响应处理..."));

                    // 额外等待确保server完成响应处理
                    Thread.sleep(1000);

                } catch (Exception e) {
                    senderException[0] = e;
                    System.err.println(logWithTimestamp("[Sender] 线程异常: " + e.getMessage()));
                } finally {
                    // 通知事务完成
                    transactionComplete.countDown();
                }
            }, "Sender-Thread");

            // 3. 启动线程
            serverThread.start();
            Thread.sleep(100); // 让server先启动
            senderThread.start();

            // 4. 等待线程完成
            serverThread.join(20000); // 20 seconds in milliseconds
            senderThread.join(20000); // 20 seconds in milliseconds

            // 5. 检查结果
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
            // 清理监听器
            receiverSerialSource.removeAllDataListeners();
            System.out.println(logWithTimestamp("✓ 已清理监听器"));
        }

        System.out.println("\n" + logWithTimestamp("多线程响应处理策略演示完成！"));
        System.out.println();
    }
}
