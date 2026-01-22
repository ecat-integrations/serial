package com.ecat.integration.SerialIntegration;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 动态字节数组缓冲区
 * 用于替代 StringBuilder，提供更高效的二进制数据存储和读取
 *
 * @author coffee
 */
public class DynamicByteArrayBuffer {
    private byte[] buffer;
    private int size;
    private final Lock lock = new ReentrantLock();
    private final float growthFactor;
    private final int maxCapacity;

    /**
     * 构造函数
     *
     * @param initialCapacity 初始容量
     * @param growthFactor 增长因子（建议 2.0f）
     * @param maxCapacity 最大缓冲区大小（超出后丢弃旧数据）
     */
    public DynamicByteArrayBuffer(int initialCapacity, float growthFactor, int maxCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        if (growthFactor <= 1.0f) {
            throw new IllegalArgumentException("Growth factor must be greater than 1.0");
        }
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Max capacity must be positive");
        }
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException("Initial capacity cannot exceed max capacity");
        }

        this.buffer = new byte[initialCapacity];
        this.size = 0;
        this.growthFactor = growthFactor;
        this.maxCapacity = maxCapacity;
    }

    /**
     * 构造函数（使用默认最大容量 8096）
     *
     * @param initialCapacity 初始容量
     * @param growthFactor 增长因子（建议 2.0f）
     */
    public DynamicByteArrayBuffer(int initialCapacity, float growthFactor) {
        this(initialCapacity, growthFactor, 8096);
    }

    /**
     * 默认构造函数：1024 初始容量，2.0 增长因子
     */
    public DynamicByteArrayBuffer() {
        this(1024, 2.0f);
    }

    /**
     * 向缓冲区追加字节数据
     *
     * @param data 要追加的数据
     * @param offset 数据偏移量
     * @param length 数据长度
     */
    public void append(byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset or length");
        }

        lock.lock();
        try {
            // 检查是否会超过最大容量
            if (size + length > maxCapacity) {
                // 计算需要丢弃的字节数
                int discardCount = (size + length) - maxCapacity;

                // 如果新数据本身超过最大容量，只保留最后 maxCapacity 字节
                if (length >= maxCapacity) {
                    int keepLength = Math.min(length, maxCapacity);
                    int newOffset = offset + length - keepLength;
                    ensureCapacity(keepLength);
                    System.arraycopy(data, newOffset, buffer, 0, keepLength);
                    size = keepLength;
                    return;
                }

                // 丢弃前面的旧数据：将剩余数据前移
                if (discardCount < size) {
                    int keepSize = size - discardCount;
                    System.arraycopy(buffer, discardCount, buffer, 0, keepSize);
                }
                size -= discardCount;
            }

            ensureCapacity(size + length);
            System.arraycopy(data, offset, buffer, size, length);
            size += length;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 向缓冲区追加字节数组
     *
     * @param data 要追加的字节数组
     */
    public void append(byte[] data) {
        if (data != null) {
            append(data, 0, data.length);
        }
    }

    /**
     * 读取并清空缓冲区
     *
     * @return 缓冲区中的所有数据
     */
    public byte[] readAndClear() {
        lock.lock();
        try {
            if (size == 0) {
                return new byte[0];
            }

            byte[] result = new byte[size];
            System.arraycopy(buffer, 0, result, 0, size);
            size = 0;
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取缓冲区内容但不清空
     *
     * @param maxLength 最大读取长度
     * @return 缓冲区数据
     */
    public byte[] peek(int maxLength) {
        lock.lock();
        try {
            if (size == 0) {
                return new byte[0];
            }

            int length = Math.min(maxLength, size);
            byte[] result = new byte[length];
            System.arraycopy(buffer, 0, result, 0, length);
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取缓冲区所有数据（不清空）
     *
     * @return 缓冲区数据副本
     */
    public byte[] getAll() {
        return peek(size);
    }

    /**
     * 读取并清空缓冲区，转换为字符串
     *
     * @param charset 字符编码
     * @return 字符串表示
     */
    public String readAndClearAsString(Charset charset) {
        byte[] data = readAndClear();
        return new String(data, charset);
    }

    /**
     * 读取并清空缓冲区，使用 UTF-8 编码
     *
     * @return UTF-8 字符串
     */
    public String readAndClearAsString() {
        return readAndClearAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 清空缓冲区
     */
    public void clear() {
        lock.lock();
        try {
            size = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前缓冲区大小
     *
     * @return 当前数据长度
     */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查缓冲区是否为空
     *
     * @return 如果没有数据则返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 获取缓冲区容量
     *
     * @return 当前缓冲区总容量
     */
    public int capacity() {
        return buffer.length;
    }

    /**
     * 获取最大缓冲区容量
     *
     * @return 最大缓冲区大小
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * 确保缓冲区有足够容量
     *
     * @param minCapacity 最小需要的容量
     */
    private void ensureCapacity(int minCapacity) {
        if (buffer.length < minCapacity) {
            int newCapacity = Math.max(
                buffer.length,
                (int)(minCapacity * growthFactor)
            );
            buffer = Arrays.copyOf(buffer, newCapacity);
        }
    }

    /**
     * 向缓冲区追加字符串（使用指定编码）
     *
     * @param data 字符串数据
     * @param charset 字符编码
     */
    public void append(String data, Charset charset) {
        if (data != null) {
            byte[] bytes = data.getBytes(charset);
            append(bytes, 0, bytes.length);
        }
    }

    /**
     * 向缓冲区追加字符串（使用 UTF-8 编码）
     *
     * @param data 字符串数据
     */
    public void append(String data) {
        append(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
