package com.ecat.integration.SerialIntegration;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * SerialSource 视图已终态关闭后的误用信号（lifecycle hard fence）。
 *
 * <p>它与“串口锁忙”是两类语义：本异常表示该 owner view 已调用
 * {@link SerialSource#closePort()}，不得再发起新 IO/取锁/监听注册；串口物理口可能
 * 仍被同口其他 view 共享。调用方按生命周期跳过处理，不得误报成 lock busy。
 */
public class SerialSourceClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SerialSourceClosedException(String operation, String portName, String identity) {
        super(operation + " rejected: serial source view closed"
                + ", port=" + portName + ", identity=" + identity);
    }

    /**
     * 剥出 CompletableFuture 链常见的包装类型后判断根因是否为视图关闭。
     */
    public static boolean isSourceClosed(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SerialSourceClosedException) {
                return true;
            }
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                current = current.getCause();
                continue;
            }
            return false;
        }
        return false;
    }
}
