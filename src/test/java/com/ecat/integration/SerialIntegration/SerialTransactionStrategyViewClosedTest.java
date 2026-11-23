package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.ecat.core.Task.LockBusySkippedException;

/**
 * 事务入口的 closed view 契约：lambda 不得执行，排队中对话以 closed 语义取消，
 * closed/lock-busy 类型分离（serial IO 最小模型 §3.4）。
 *
 * <p>旧用例（mock acquire 桥接）→ 新用例（真实端口/视图）映射：
 * closedSourceRejects... → 提交点 assertViewActive 拒绝；
 * sourceClosedAfterAcquireReleasesKey...（acquire 授予与 close 的竞态）→
 * 「排队中对话被 session 注销取消」——同一事故面（close 后对话不得执行、不得占口）
 * 在新模型的结构位：session 注销事件取消该 session 全部未开始对话。
 */
public class SerialTransactionStrategyViewClosedTest {

    /** 真实端口对象（构造零 jSerialComm 副作用）。 */
    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("TX-CLOSED-PORT", 9600, 8, 1, 0), 3, null);
    }

    /** 真实视图（挂靠注册对假端口名 open 失败无害）。 */
    private SerialSource newSource(SerialSourcePort port, String identity) {
        return new SerialSource(port, identity);
    }

    @Test
    public void closedSourceRejectsWithoutRunningLambda() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource source = newSource(port, "device-a");
        AtomicBoolean lambdaRan = new AtomicBoolean(false);

        source.closePort();

        CompletableFuture<Boolean> result = SerialTransactionStrategy.executeWithLambda(
                source, src -> {
                    lambdaRan.set(true);
                    return CompletableFuture.completedFuture(true);
                });
        try {
            result.get(1, TimeUnit.SECONDS);
            fail("closed source 事务必须失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        assertFalse("closed source 的事务 lambda 不得执行", lambdaRan.get());
    }

    /**
     * 排队中对话的 closed 取消：口被 HELD 占用使对话排队，随后视图关闭——未开始对话
     * 必须以 closed 语义异常完成（lambda 不执行），口随后照常接受下一场（不占口）。
     * （预挂一个幸存视图，防止本视图注销触发退役；下一场也经幸存视图提交。）
     */
    @Test
    public void queuedConversationCancelledWithClosedSemantics_whenViewCloses() throws Exception {
        SerialSourcePort port = newPort();
        SerialSource survivor = newSource(port, "survivor");
        SerialSource source = newSource(port, "device-a");
        String held = holdPort(port);
        AtomicBoolean lambdaRan = new AtomicBoolean(false);

        CompletableFuture<Boolean> queued = SerialTransactionStrategy.executeWithLambda(
                source, src -> {
                    lambdaRan.set(true);
                    return CompletableFuture.completedFuture(true);
                });
        awaitQueued(port, 1);

        source.closePort();

        try {
            queued.get(2, TimeUnit.SECONDS);
            fail("排队中对话在视图关闭后必须 closed 语义取消");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }
        assertFalse("被取消对话的 lambda 不得执行", lambdaRan.get());
        assertTrue("口占用者释放", port.release(held));
        assertTrue("口占用者释放后口应照常服务下一场",
                SerialTransactionStrategy.executeWithLambda(
                        survivor, src -> CompletableFuture.completedFuture(true))
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    public void pollingDistinguishesClosedAndLockBusy() throws Exception {
        SerialSourcePort port = newPort();
        // 先挂 busy 视图与占用者（保证 closed 视图注销不触发退役）
        SerialSource busy = newSource(port, "poll-busy");
        String held = holdPort(port);
        // closed polling：提交点拒绝，closed 语义异常 future
        SerialSource closed = newSource(port, "poll-closed");
        closed.closePort();
        CompletableFuture<Boolean> closedResult = SerialTransactionStrategy.executePolling(
                closed, 100, src -> CompletableFuture.completedFuture(true), 1000);
        try {
            closedResult.get(1, TimeUnit.SECONDS);
            fail("closed polling 事务必须 lifecycle 失败");
        } catch (Exception expected) {
            assertTrue(SerialSourceClosedException.isSourceClosed(expected.getCause()));
        }

        // lock-busy polling：排队预算耗尽弃轮，跳过语义（非 closed）
        CompletableFuture<Boolean> busyResult = SerialTransactionStrategy.executePolling(
                busy, 150, src -> CompletableFuture.completedFuture(true), 60_000L);
        try {
            busyResult.get(5, TimeUnit.SECONDS);
            fail("lock busy polling 必须失败");
        } catch (Exception expected) {
            assertFalse(SerialSourceClosedException.isSourceClosed(expected.getCause()));
            assertTrue(LockBusySkippedException.isLockBusySkip(expected.getCause()));
        }
        assertTrue(port.release(held));
    }

    // ==================== helpers ====================

    /** 异线程占用口（HELD 取得后线程退出；释放由测试按 key 执行）。 */
    private String holdPort(SerialSourcePort port) throws Exception {
        final String[] held = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                held[0] = port.acquire(10, TimeUnit.SECONDS);
            } finally {
                done.countDown();
            }
        }, "view-closed-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(held[0]);
        return held[0];
    }

    /** 排队数事件等待（deadline 轮询验证事件已发生，超时即失败）。 */
    private static void awaitQueued(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.queuedConversations() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("对话未在 5s 内入队: expected>=" + expected
                        + ", actual=" + port.queuedConversations());
            }
        }
    }
}
