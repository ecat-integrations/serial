package com.ecat.integration.SerialIntegration.SendReadStrategy;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ResponseHandlingContext for binary data handling.
 * Uses ByteArrayOutputStream for efficient byte array operations.
 *
 * @param <T> The type of value being set during the operation
 * @author coffee
 */
public class ByteResponseHandlingContext<T> {
    private final ByteArrayOutputStream receiveBuffer;
    private final AtomicBoolean finishedFlag;
    private final AtomicLong startTime;
    private final T newValue;

    /**
     * Creates a new context for handling binary serial responses.
     *
     * @param newValue The value to be set after the operation completes
     */
    public ByteResponseHandlingContext(T newValue) {
        this.receiveBuffer = new ByteArrayOutputStream();
        this.finishedFlag = new AtomicBoolean(false);
        this.startTime = new AtomicLong(System.currentTimeMillis());
        this.newValue = newValue;
    }

    /**
     * Gets the receive buffer for storing incoming binary data.
     *
     * @return the ByteArrayOutputStream for receiving data
     */
    public ByteArrayOutputStream getReceiveBuffer() {
        return receiveBuffer;
    }

    /**
     * Gets the received data as a byte array.
     *
     * @return the received bytes
     */
    public byte[] getReceiveBytes() {
        return receiveBuffer.toByteArray();
    }

    /**
     * Gets the flag indicating if the operation is finished.
     *
     * @return the AtomicBoolean finish flag
     */
    public AtomicBoolean getFinishedFlag() {
        return finishedFlag;
    }

    /**
     * Gets the start time of the operation.
     *
     * @return the AtomicLong start time in milliseconds
     */
    public AtomicLong getStartTime() {
        return startTime;
    }

    /**
     * Gets the new value to be set after the operation completes.
     *
     * @return the new value
     */
    public T getNewValue() {
        return newValue;
    }
}
