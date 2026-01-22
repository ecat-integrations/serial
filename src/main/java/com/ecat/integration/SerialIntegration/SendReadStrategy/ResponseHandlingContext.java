package com.ecat.integration.SerialIntegration.SendReadStrategy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ResponseHandlingContext is a context class used to handle the response
 * from a serial communication operation.
 * 
 * It contains a receive buffer to store the incoming data,
 * a flag to indicate if the operation is finished,
 * a start time to track the duration of the operation,
 * and a new value that is set during the operation,
 * which can be used for updating the state after the operation is complete.
 * 
 * @author coffee
 */
public class ResponseHandlingContext<T> {
    private final StringBuilder receiveBuffer;
    private final AtomicBoolean finishedFlag;
    private final AtomicLong startTime;
    private final T newValue; // 需要设置的新值，供最后更新使用

    public ResponseHandlingContext(T newValue) {
        this.receiveBuffer = new StringBuilder();
        this.finishedFlag = new AtomicBoolean(false);
        this.startTime = new AtomicLong(System.currentTimeMillis());
        this.newValue = newValue;
    }

    public StringBuilder getReceiveBuffer() {
        return receiveBuffer;
    }

    public AtomicBoolean getFinishedFlag() {
        return finishedFlag;
    }

    public AtomicLong getStartTime() {
        return startTime;
    }

    public T getNewValue() {
        return newValue;
    }
}
