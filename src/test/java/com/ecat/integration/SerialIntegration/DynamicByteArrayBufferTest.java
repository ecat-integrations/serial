package com.ecat.integration.SerialIntegration;

import org.junit.Before;
import org.junit.Test;
import org.junit.After;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * DynamicByteArrayBuffer 单元测试
 * 验证动态字节数组缓冲区的功能和性能
 *
 * @author coffee
 */
public class DynamicByteArrayBufferTest {

    private DynamicByteArrayBuffer buffer;

    @Before
    public void setUp() {
        buffer = new DynamicByteArrayBuffer(10, 2.0f); // 小初始容量用于测试扩容
    }

    @After
    public void tearDown() {
        buffer = null;
    }

    @Test
    public void testInitialSize() {
        assertEquals("Initial size should be 0", 0, buffer.size());
        assertTrue("Should be empty initially", buffer.isEmpty());
        assertEquals("Initial capacity should be 10", 10, buffer.capacity());
    }

    @Test
    public void testAppendSingleByteArray() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        buffer.append(data, 0, data.length);

        assertEquals("Size should be 5", 5, buffer.size());
        assertFalse("Should not be empty", buffer.isEmpty());
    }

    @Test
    public void testAppendByteArrayWithOffset() {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        buffer.append(data, 2, 5); // append "23456"

        assertEquals("Size should be 5", 5, buffer.size());
        byte[] result = buffer.getAll();
        String resultStr = new String(result, StandardCharsets.UTF_8);
        assertEquals("Content should be '23456'", "23456", resultStr);
    }

    @Test
    public void testAppendNullData() {
        // append(byte[]) method should handle null gracefully
        buffer.append((byte[])null);
        assertEquals("Size should remain 0 after appending null", 0, buffer.size());

        // But append(byte[], int, int) should throw NPE
        try {
            buffer.append(null, 0, 0);
            fail("Should throw NullPointerException for null data with offset");
        } catch (NullPointerException e) {
            // Expected
        }
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testAppendInvalidOffset() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        buffer.append(data, -1, 3);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testAppendInvalidLength() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        buffer.append(data, 1, 10); // length exceeds array size
    }

    @Test
    public void testReadAndClear() {
        byte[] data1 = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = " World".getBytes(StandardCharsets.UTF_8);

        buffer.append(data1);
        buffer.append(data2);

        assertEquals("Size should be 11", 11, buffer.size());

        byte[] result = buffer.readAndClear();

        assertEquals("Result length should be 11", 11, result.length);
        String resultStr = new String(result, StandardCharsets.UTF_8);
        assertEquals("Content should be 'Hello World'", "Hello World", resultStr);

        // Buffer should be empty after readAndClear
        assertEquals("Size should be 0 after readAndClear", 0, buffer.size());
        assertTrue("Should be empty after readAndClear", buffer.isEmpty());
    }

    @Test
    public void testPeek() {
        byte[] data = "Hello World".getBytes(StandardCharsets.UTF_8);
        buffer.append(data);

        // Peek all
        byte[] result1 = buffer.peek(100);
        assertEquals("Should return all data", "Hello World", new String(result1, StandardCharsets.UTF_8));
        assertEquals("Size should not change", 11, buffer.size());

        // Peek limited
        byte[] result2 = buffer.peek(5);
        assertEquals("Should return first 5 bytes", "Hello", new String(result2, StandardCharsets.UTF_8));
        assertEquals("Size should not change", 11, buffer.size());
    }

    @Test
    public void testClear() {
        buffer.append("Test data".getBytes(StandardCharsets.UTF_8), 0, "Test data".length());
        assertFalse("Should not be empty", buffer.isEmpty());

        buffer.clear();
        assertTrue("Should be empty after clear", buffer.isEmpty());
        assertEquals("Size should be 0 after clear", 0, buffer.size());
    }

    @Test
    public void testBufferExpansion() {
        // Initial capacity is 10, append more to trigger expansion
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            sb.append((char)('a' + i % 26));
        }
        String testData = sb.toString();

        buffer.append(testData.getBytes(StandardCharsets.UTF_8));

        assertEquals("Size should match appended data", 25, buffer.size());
        assertTrue("Capacity should have expanded", buffer.capacity() >= 25);

        byte[] result = buffer.readAndClear();
        assertEquals("Content should match", testData, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    public void testReadAndClearAsString() {
        byte[] testData = "测试数据".getBytes(StandardCharsets.UTF_8);
        buffer.append(testData, 0, testData.length);

        String result = buffer.readAndClearAsString();

        assertEquals("Should read Chinese characters correctly", "测试数据", result);
        assertTrue("Should be empty after read", buffer.isEmpty());
    }

    @Test
    public void testAppendString() {
        // Test the string append functionality
        DynamicByteArrayBuffer stringBuffer = new DynamicByteArrayBuffer();
        stringBuffer.append("Hello");
        stringBuffer.append(" ");
        stringBuffer.append("世界");

        String result = stringBuffer.readAndClearAsString();
        assertEquals("Should concatenate strings correctly", "Hello 世界", result);
    }

    @Test
    public void testEmptyBufferOperations() {
        // Test operations on empty buffer
        assertEquals("Empty buffer should return empty array", 0, buffer.readAndClear().length);
        assertEquals("Empty peek should return empty array", 0, buffer.peek(10).length);
        assertEquals("Empty readAsString should return empty string", "", buffer.readAndClearAsString());
    }

    @Test
    public void testConstructorValidation() {
        try {
            new DynamicByteArrayBuffer(0, 2.0f);
            fail("Should throw exception for zero initial capacity");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new DynamicByteArrayBuffer(10, 1.0f);
            fail("Should throw exception for growth factor <= 1.0");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new DynamicByteArrayBuffer(10, 2.0f, 0);
            fail("Should throw exception for zero max capacity");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new DynamicByteArrayBuffer(100, 2.0f, 50);
            fail("Should throw exception when initial capacity exceeds max capacity");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testGetMaxCapacity() {
        // Default max capacity should be 8096
        assertEquals("Default max capacity should be 8096", 8096, buffer.getMaxCapacity());

        // Custom max capacity
        DynamicByteArrayBuffer customBuffer = new DynamicByteArrayBuffer(10, 2.0f, 100);
        assertEquals("Custom max capacity should be 100", 100, customBuffer.getMaxCapacity());
    }

    @Test
    public void testMaxCapacityNormalAppend() {
        // Create buffer with max capacity of 20
        DynamicByteArrayBuffer limitedBuffer = new DynamicByteArrayBuffer(5, 2.0f, 20);

        // Append data within max capacity
        byte[] data1 = "Hello".getBytes(StandardCharsets.UTF_8); // 5 bytes
        limitedBuffer.append(data1);

        assertEquals("Size should be 5", 5, limitedBuffer.size());
        assertEquals("Content should be 'Hello'", "Hello",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));

        // Append more data, still within limit
        byte[] data2 = " World!".getBytes(StandardCharsets.UTF_8); // 7 bytes
        limitedBuffer.append(data2);

        assertEquals("Size should be 12", 12, limitedBuffer.size());
        assertEquals("Content should be 'Hello World!'", "Hello World!",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));
    }

    @Test
    public void testMaxCapacityOverflowDiscardOldest() {
        // Create buffer with max capacity of 10
        DynamicByteArrayBuffer limitedBuffer = new DynamicByteArrayBuffer(5, 2.0f, 10);

        // Append "ABCDE" (5 bytes)
        limitedBuffer.append("ABCDE".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 5", 5, limitedBuffer.size());

        // Append "FGHIJ" (5 bytes), total would be 10 - exactly at max
        limitedBuffer.append("FGHIJ".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 10", 10, limitedBuffer.size());
        assertEquals("Content should be 'ABCDEFGHIJ'", "ABCDEFGHIJ",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));

        // Append "KLMNO" (5 bytes), would exceed max - oldest 5 bytes discarded
        limitedBuffer.append("KLMNO".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 10 (max capacity)", 10, limitedBuffer.size());
        // Should have discarded "ABCDE", kept "FGHIJ" and added "KLMNO"
        assertEquals("Oldest data should be discarded", "FGHIJKLMNO",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));
    }

    @Test
    public void testMaxCapacityMultipleOverflows() {
        // Create buffer with max capacity of 15
        DynamicByteArrayBuffer limitedBuffer = new DynamicByteArrayBuffer(5, 2.0f, 15);

        // Fill to 10 bytes
        limitedBuffer.append("ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 10", 10, limitedBuffer.size());

        // Add 10 more, should overflow by 5
        limitedBuffer.append("KLMNOPQRST".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 15 (max capacity)", 15, limitedBuffer.size());
        // Should have "FGHIJKLMNOPQRST" (discarded "ABCDE")
        assertEquals("Should discard oldest 5 bytes", "FGHIJKLMNOPQRST",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));

        // Add 10 more, should overflow by 10
        limitedBuffer.append("UVWXYZ12345".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 15 (max capacity)", 15, limitedBuffer.size());
        // Should have "QRSTUVWXYZ12345" (discarded "FGHIJKLMNOP"), kept last 15
        assertEquals("Should discard oldest 10 bytes", "QRSTUVWXYZ12345",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));
    }

    @Test
    public void testMaxCapacityWithLargeSingleAppend() {
        // Create buffer with max capacity of 20
        DynamicByteArrayBuffer limitedBuffer = new DynamicByteArrayBuffer(5, 2.0f, 20);

        // Append 10 bytes
        limitedBuffer.append("ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8));

        // Append 26 bytes - length (26) >= maxCapacity (20), keep last 20 bytes
        // "KLMNOPQRSTUVWXYZ0123456789" has 26 chars, we keep last 20 starting from 'Q'
        limitedBuffer.append("KLMNOPQRSTUVWXYZ0123456789".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be 20 (max capacity)", 20, limitedBuffer.size());
        // Should have the last 20 bytes since length >= maxCapacity
        assertEquals("Should keep last 20 bytes when single append >= maxCapacity",
            "QRSTUVWXYZ0123456789",
            new String(limitedBuffer.getAll(), StandardCharsets.UTF_8));
    }

    @Test
    public void testMaxCapacityDiscardDoesNotAffectCapacity() {
        // Create buffer with max capacity of 20
        DynamicByteArrayBuffer limitedBuffer = new DynamicByteArrayBuffer(5, 2.0f, 20);

        // Fill beyond max to trigger expansion
        limitedBuffer.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8));
        assertEquals("Size should be limited to 20", 20, limitedBuffer.size());

        // Capacity may have expanded, but size is limited
        assertTrue("Capacity should be at least max capacity", limitedBuffer.capacity() >= 20);
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        final int threadCount = 10;
        final int iterations = 100;
        Thread[] threads = new Thread[threadCount];

        // Multiple threads appending data
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < iterations; j++) {
                    String data = threadId + "-" + j + ",";
                    buffer.append(data.getBytes(StandardCharsets.UTF_8));
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all data was appended
        byte[] result = buffer.readAndClear();
        String resultStr = new String(result, StandardCharsets.UTF_8);

        // Should have threadCount * iterations entries
        int commaCount = 0;
        for (char c : resultStr.toCharArray()) {
            if (c == ',') commaCount++;
        }
        assertEquals("Should have correct number of entries", threadCount * iterations, commaCount);
    }

    @Test
    public void testPerformanceCompareWithStringBuilder() {
        // Simple performance comparison
        int dataLength = 10000;
        byte[] testData = new byte[dataLength];
        for (int i = 0; i < dataLength; i++) {
            testData[i] = (byte)('a' + i % 26);
        }

        DynamicByteArrayBuffer byteBuffer = new DynamicByteArrayBuffer();
        StringBuilder stringBuilder = new StringBuilder();

        // Test DynamicByteArrayBuffer
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            byteBuffer.append(testData);
            byteBuffer.readAndClear();
        }
        long byteBufferTime = System.nanoTime() - startTime;

        // Test StringBuilder (simulate old approach)
        startTime = System.nanoTime();
        String testString = new String(testData, StandardCharsets.UTF_8);
        for (int i = 0; i < 100; i++) {
            stringBuilder.append(testString);
            String result = stringBuilder.toString();
            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
            stringBuilder.setLength(0);
        }
        long stringBuilderTime = System.nanoTime() - startTime;

        // Byte buffer should be faster (no encoding/decoding)
        assertTrue("DynamicByteArrayBuffer should be faster", byteBufferTime < stringBuilderTime);

        System.out.println("DynamicByteArrayBuffer time: " + byteBufferTime / 1_000_000 + " ms");
        System.out.println("StringBuilder time: " + stringBuilderTime / 1_000_000 + " ms");
        System.out.println("Performance improvement: " +
            (stringBuilderTime / (double)byteBufferTime) + "x");
    }
}
