package com.ecat.integration.SerialIntegration;

import com.ecat.core.Utils.TestTools;
import org.junit.Test;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 红测试：串口总线仲裁锁「等待队列死 key 泄漏传染」缺陷（acquire skip 分支）。
 *
 * 缺陷机理：acquire() 被 signal 唤醒后若发现队头不是自己（"Wait queue changed,
 * skip acquisition"），直接 return null，但不把自己的 requestKey 从 waitQueue
 * 摘除。该 key 的线程已经返回，成为永久占据队头的死 key。此后每次 release 的
 * signal 唤醒的都是真实等待者，但它们 peek 到死 key 同样 skip、同样不摘除——
 * 每个被唤醒的合法等待者都被无谓拒绝，且各自的 key 也变成新的尸体，队列只增
 * 不减，最终 maxWaiters 名额被死 key 耗尽，新请求连排队资格都没有
 * （"Max waiters exceeded"），尽管场上一个真实等待者都不存在。
 *
 * 确定性编排：
 *  1. 主线程快速路径取得 keyA；
 *  2. 反射向 waitQueue 注入死 key，精确构造「某等待者已 skip 返回但 key 残留
 *     队头」的状态（该状态由上述缺陷自然产生，此处直接构造以免去不可控竞态）；
 *  3. 线程D 作为唯一真实等待者入队挂起（锁内确认队列长度=2 才继续，signal 必达）；
 *  4. release(keyA) -> signal 唤醒 D -> D 看到队头是死 key -> skip 返回 null，
 *     keyD 残留；此刻锁实际空闲，D 却被拒绝；
 *  5. 主线程立即再次快速路径取得 keyE（对照证明：锁空闲可用，D 属于被冤枉拒绝）；
 *  6. 线程F 在 keyE 持有期间请求：缺陷下队列两具尸体已占满 maxWaiters=2，
 *     F 连入队资格都没有；F 用短超时自驱返回，不依赖任何 signal，
 *     「缺陷/修复」两种实现下都会在期限内返回，编排无竞态。
 * 全程唯一时序敏感点是 D 的挂起，已用锁内队列长度观察门控。
 *
 * 主断言在缺陷存在时失败（RED）：返回 null 的等待者不得把自己的 key 留在队列。
 */
public class BusArbitrationQueueLeakRedTest {

    private static final String DEAD_KEY = "dead-key-from-skipped-waiter";

    @Test
    public void skipBranchLeaksWaiterKey_deadQueueHeadDeniesAllSubsequentWaiters() throws Exception {
        final SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("PORT-UNDER-TEST", 9600, 8, 1, 0), 2, null);
        final ReentrantLock portLock = (ReentrantLock) TestTools.getPrivateField(port, "lock");

        // 持有者取得锁
        final String keyA = port.acquire(2, TimeUnit.SECONDS);
        assertNotNull("无竞争下主线程必须取得锁", keyA);

        // 注入死 key：模拟「skip 分支返回 null 但 key 残留队头」的缺陷状态
        portLock.lock();
        try {
            waitQueueOf(port).add(DEAD_KEY);
        } finally {
            portLock.unlock();
        }

        // 唯一真实等待者 D：入队（队列=[DEAD, keyD]）并挂起
        final String[] keyD = new String[1];
        final CountDownLatch dDone = new CountDownLatch(1);
        final Throwable[] dError = new Throwable[1];
        start("waiter-D", dDone, dError, () -> keyD[0] = port.acquire(2, TimeUnit.SECONDS));
        awaitWaiterParked(port, portLock, 2);

        assertTrue("release(keyA) 必须成功", port.release(keyA)); // signal 唤醒 D -> D 走 skip 分支
        assertTrue("线程D的acquire必须在期限内返回", dDone.await(10, TimeUnit.SECONDS));
        assertNull("线程D不应抛异常", dError[0]);
        assertNull("锁已空闲，唯一真实等待者 D 却因死 key 挡在队头被拒绝", keyD[0]);

        // 对照：锁确实空闲可用（D 属于被冤枉拒绝）
        final String keyE = port.acquire(2, TimeUnit.SECONDS);
        assertNotNull("D 被拒后锁应可立即取得", keyE);

        // F：keyE 持有期间请求。缺陷下队列 [DEAD, keyD] 已占满 maxWaiters=2，
        // F 连排队资格都没有直接被拒；修复下队列只剩 [DEAD]，F 能入队并在短超时后自然返回。
        final String[] keyF = new String[1];
        final CountDownLatch fDone = new CountDownLatch(1);
        final Throwable[] fError = new Throwable[1];
        start("waiter-F", fDone, fError, () -> keyF[0] = port.acquire(800, TimeUnit.MILLISECONDS));
        assertTrue("线程F的acquire必须在期限内返回", fDone.await(10, TimeUnit.SECONDS));
        assertNull("线程F不应抛异常", fError[0]);
        assertNull("场上无任何真实等待者，F 仍被尸体占满的容量拒绝", keyF[0]);

        assertTrue("release(keyE) 必须成功", port.release(keyE));

        // ===== 主断言（RED）：已返回 null 的等待者 key 不得残留在队列 =====
        portLock.lock();
        try {
            final Queue<String> queue = waitQueueOf(port);
            assertEquals("队头只能是注入的死 key", DEAD_KEY, queue.peek());
            assertEquals("skip 分支返回 null 前必须摘除自身 key：队列应只剩死 key 一项；实际队列="
                    + queue + "（已返回的等待者 key 泄漏为尸体，队列永不清空、传染后续等待者）",
                    1, queue.size());
        } finally {
            portLock.unlock();
        }
    }

    /**
     * 在持有 portLock 的状态下观察 waitQueue，等待其长度达到 expectedSize。
     * 条件单调（等待者只入队不消失，直到后续 signal），期限内未达成即硬失败——
     * 语义等价 latch：不是「睡固定时间猜事件发生」，而是「观察到事件已发生才继续」，
     * 且绝不允许在前置条件未成立时静默继续（不会假绿）。
     */
    private static void awaitWaiterParked(Object owner, ReentrantLock portLock, int expectedSize)
            throws Exception {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            portLock.lock();
            try {
                if (waitQueueOf(owner).size() == expectedSize) {
                    return;
                }
            } finally {
                portLock.unlock();
            }
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new AssertionError("等待者未在期限内入队并挂起（期望 waitQueue 长度="
                        + expectedSize + "），编排前置条件未成立");
            }
            LockSupport.parkNanos(500_000); // 0.5ms 让出，等等待者完成入队+挂起
        }
    }

    @SuppressWarnings("unchecked")
    private static Queue<String> waitQueueOf(Object owner) throws Exception {
        return (Queue<String>) TestTools.getPrivateField(owner, "waitQueue");
    }

    private static void start(String name, CountDownLatch done, Throwable[] error, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable x) {
                error[0] = x;
            } finally {
                done.countDown();
            }
        }, name);
        t.start();
    }
}
