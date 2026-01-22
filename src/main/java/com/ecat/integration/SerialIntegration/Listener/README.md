# Serial Listener 模块

本目录包含串口监听器相关的所有类，负责处理串口数据接收和事件分发。

## 类关系图

```
SerialDataListener (接口)
    ↑
    ├── PooledSerialDataListener (String 数据)
    │     └── PoolableListener
    │
    └── BytePooledSerialDataListener (byte[] 数据)
          └── PoolableListener

GenericSerialDataListenerPool<T extends PoolableListener>
    ├── STRING_POOL: GenericSerialDataListenerPool<PooledSerialDataListener>
    └── BYTE_POOL: GenericSerialDataListenerPool<BytePooledSerialDataListener>

SerialListenerPools (单例管理类)
    ├── STRING_POOL
    └── BYTE_POOL

SerialDataListenerPool (便捷访问类)
    └── 委托给 SerialListenerPools.STRING_POOL

SerialSourceEventAdapter
    └── 将 jSerialComm 事件适配为 SerialDataListener 通知
```

## 核心接口

### SerialDataListener
串口数据监听器接口，定义数据接收和错误处理回调。

### PoolableListener
可池化监听器接口，定义对象池所需的 `cleanup()` 和 `isInUse()` 方法。

## 实现类

### PooledSerialDataListener
字符串数据监听器，用于处理基于 String 的串口通信。

### BytePooledSerialDataListener
字节数据监听器，用于处理基于 byte[] 的串口通信。

## 池管理

### GenericSerialDataListenerPool
泛型监听器池，使用全局共享池管理监听器实例，避免 CompletableFuture 回调中的跨线程问题。

### SerialListenerPools
单例管理类，统一管理字符串和字节两种监听器池：
- `STRING_POOL`: 管理 PooledSerialDataListener
- `BYTE_POOL`: 管理 BytePooledSerialDataListener

### SerialDataListenerPool
便捷访问类，委托给 SerialListenerPools.STRING_POOL。

## 事件适配器

### SerialSourceEventAdapter
将 jSerialComm 的 `SerialPortEvent` 适配为 `SerialDataListener` 通知，实现中断驱动模式。

## 使用方式

### 字符串模式 (DefaultResponseHandlerStrategy)
```java
PooledSerialDataListener listener = SerialDataListenerPool.acquire();
listener.reset(context, responseFuture, serialSource, checkFunction);
serialSource.addDataListener(listener);
// ... 数据接收完成后
SerialDataListenerPool.release(listener);
```

### 字节模式 (ByteResponseHandlerStrategy)
```java
BytePooledSerialDataListener listener = SerialListenerPools.BYTE_POOL.acquire();
listener.reset(context, responseFuture, serialSource, checkFunction);
serialSource.addDataListener(listener);
// ... 数据接收完成后
SerialListenerPools.BYTE_POOL.release(listener);
```

## 设计原则

1. **对象池模式**: 复用监听器实例，减少 GC 压力
2. **全局共享池**: 避免跨线程释放问题
3. **接口隔离**: String 和 byte[] 两种数据类型分别处理
4. **类型安全**: 使用泛型确保类型安全
