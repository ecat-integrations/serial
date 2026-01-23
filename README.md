# ecat 串口管理集成

为其他集成提供串口的控制访问功能。

## 特点

1. **消除CPU轮询开销**：中断驱动模式，大幅降低CPU占用
2. **纯二进制处理**：`ByteResponseHandlerStrategy` 直接处理 `byte[]`，避免字符串转换问题
3. **环境自适应**：自动检测测试环境并切换到兼容模式
4. **对象池优化**：线程本地池管理监听器，减少GC压力

## 最佳实践

### send-receive 异步读写模式

1. 使用 `ByteResponseHandlerStrategy` 进行二进制数据处理
2. 设计自定义的 `checkByteResponse` 以适应具体协议

```java
private byte[] checkByteResponse(byte[] response) {
    // 检查帧头和长度
    if (response.length >= 5 && response[0] == (byte)0xEB && response[1] == (byte)0x90) {
        int length = response[3] & 0xFF;
        if (response.length >= length + 5){
            return response;  // 完整帧
        }
    }
    return null;  // 不完整，继续等待
}

private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
    if(context == null || context.getFinishedFlag() != true ) {
        return false; // 无效响应
    }
    byte[] response = context.getReceiveBytes();
    // 处理响应数据
    System.out.println("Received response: " + Arrays.toString(response));
    return true; // 处理成功
}

private Boolean handleException(Throwable ex) {
    log.error("Timeout or error waiting for response for getDatas() on device " + this.getId(), ex);
    return false;
}

ByteResponseHandlerStrategy<byte[]> strategy = new ByteResponseHandlerStrategy<>(
    serialSource,
    this::processResponse,      // Function<ByteResponseHandlingContext<T>, Boolean>
    this::checkByteResponse,     // Function<byte[], byte[]>
    this::handleException        // Function<Throwable, Boolean>
);

// 发送数据并处理响应
serialSource.asyncSendRead(
    commandBytes,
).thenAccept(result -> {
    return strategy.handleResponse(
        new ByteResponseHandlingContext<>(result)
    );
});

```

### server监听反馈模式

1. 注册 `SerialDataListener` 监听数据
2. 在 `onDataReceived` 中处理数据并发送响应

```java

serialSource.addDataListener(new SerialDataListener() {
    @Override
    public void onDataReceived(byte[] data, int length) {
        handleIncomingData(data, length);
    }

    @Override
    public void onError(Exception ex) {
        log.error("Serial communication error: " + ex.getMessage());
    }
});


/**
 * Handle incoming data from device.
 * Parses the command and sends a response with current attribute values.
 */
private void handleIncomingData(byte[] data, int length) {
    try {
        // Generate response frame - ASCII format
        byte[] response = ProtocolHandler.generateResponseASCII(
            LocalDateTime.now(),
            "test data"
        );
        log.debug("Generated ASCII-encoded response for production device");

        // Send response asynchronously
        serialSource.asyncSendData(response)
            .thenAccept(success -> {
                if (success) {
                    log.debug("Response sent successfully to device (" +
                        response.length + " bytes)");
                } else {
                    log.warn("Failed to send response to device");
                }
            })
            .exceptionally(ex -> {
                log.error("Error sending response: " + ex.getMessage());
                return null;
            });

    } catch (Exception e) {
        log.error("Error handling command: " + e.getMessage(), e);
    }
}

```


## 核心组件

### SerialSource
串口源，提供发送/接收、监听器管理等功能。

### ByteResponseHandlerStrategy（推荐）
**主要响应处理策略**，直接处理二进制数据，避免字符串转换兼容性问题。

```java
// 创建策略
ByteResponseHandlerStrategy<byte[]> strategy = new ByteResponseHandlerStrategy<>(
    serialSource,
    this::processResponse,      // Function<ByteResponseHandlingContext<T>, Boolean>
    this::checkByteResponse,     // Function<byte[], byte[]>
    this::handleException        // Function<Throwable, Boolean>
);
```

### DefaultResponseHandlerStrategy（已废弃）

**仅用于向后兼容，将在未来版本移除**。新集成请勿使用。

---

## 使用例子

- [二进制处理示例](src/test/java/com/ecat/integration/SerialIntegration/bytes/ByteCommunicationExample.java)
- [性能测试](src/test/java/com/ecat/integration/SerialIntegration/bytes/MultiPortConcurrencyByteTest.java)

## 模拟测试环境

```bash
# 创建虚拟串口对
sudo socat -d -d pty,raw,echo=0,link=/dev/ttyV0 pty,raw,echo=0,link=/dev/ttyV1

# set privilege
sudo chmod 666 /dev/pts/7 /dev/pts/8
sudo chmod 666 /dev/pts/*

# testing tunnel communication
sudo cat /dev/ttyV1
sudo sh -c 'echo "测试虚拟串口通信" > /dev/ttyV0'
测试虚拟串口通信
测试虚拟串口通信


```

## 性能测试环境

```bash

# 循环创建20对串口对（V0↔V1 ~ V38↔V39）
for i in {0..38..2}; do
    # 直接用root权限执行socat（避免sudo分叉进程）
    sudo bash -c "socat -d -d pty,raw,echo=0,link=/dev/ttyV$i pty,raw,echo=0,link=/dev/ttyV$((i+1)) &"
done

# 批量赋予串口读写权限
sudo chmod 666 /dev/ttyV{0..39}

# 只统计socat核心进程数量（应该输出20）
ps -ef | grep "socat -d -d pty" | grep -v grep | wc -l

# 杀掉所有socat进程（包括sudo包装的）
sudo pkill -9 socat
# 清理残留的串口符号链接
sudo rm -f /dev/ttyV{0..39}

```

## 更新日志
- v1.1.0 (2025-12-27)
  - 引入 `ByteResponseHandlerStrategy`，支持纯二进制数据处理
  - 废弃 `DefaultResponseHandlerStrategy`，计划未来版本移除

- v1.0.0 (2025-12-10)
  - 初始版本，支持串口中断驱动和轮询模式
  - 提供 `DefaultResponseHandlerStrategy` 响应处理策略
  - 实现线程本地监听器池，减少GC压力
  - 优化多线程监听器池，提升高并发性能
  - 增强测试环境自动检测和适配能力

## 协议声明
1. 核心依赖：本插件基于 **ECAT Core**（Apache License 2.0）开发，Core 项目地址：https://github.com/ecat-project/ecat-core。
2. 插件自身：本插件的源代码采用 [Apache License 2.0] 授权。
3. 合规说明：使用本插件需遵守 ECAT Core 的 Apache 2.0 协议规则，若复用 ECAT Core 代码片段，需保留原版权声明。

### 许可证获取
- ECAT Core 完整许可证：https://github.com/ecat-project/ecat-core/blob/main/LICENSE
- 本插件许可证：./LICENSE

