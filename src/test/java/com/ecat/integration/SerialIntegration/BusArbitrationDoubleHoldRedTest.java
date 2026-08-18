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
 * 红测试：串口总线仲裁锁「双持有」缺陷（SerialSourcePort.acquire 唤醒分支）。
 *
 * 缺陷机理：acquire() 在 condition.await 被 signal 唤醒后，只检查 waitQueue 队头
 * 是否等于自己的 requestKey，不复查 currentKey 是否已被重新占用。若持有者
 * release() 发出 signal 之后、等待者重新进入临界区之前，另一请求经快速路径
 * （currentKey==null 分支）取得锁，等待者醒来仍会执行 currentKey = requestKey，
 * 无声覆盖快速路径持有者的 key。后果：两个不同 key 同时被当作持有者；被覆盖方
 * 此后的 release 永远返回 false（Invalid release key），其授权被窃取且无法释放。
 *
 * 确定性编排（无 sleep 猜测、无需统计成功率）：
 * ReentrantLock 可重入——测试线程持有内部锁期间调用 release()/acquire() 时，
 * 其内部的 lock.lock() 直接重入成功。由此把竞态窗口钉死：
 *  1. 线程A 无竞争经快速路径取得 keyA；
 *  2. 线程B 入队并挂起在 condition.await（锁内读 waitQueue 确认长度为 1 才继续，
 *     保证后续 signal 必然送达 B，不落空）；
 *  3. 测试线程持有内部锁期间：release(keyA) 置 currentKey=null 并 signal
 *     （B 被排进同步队列，但测试线程仍持锁，B 无法前行）；随后仍在持锁下调用
 *     acquire() —— 可重入快速路径取得 keyMain（currentKey=keyMain），等价于
 *     生产时序中竞争线程在 signal 之后、等待者重入临界区之前抢到锁；
 *  4. 测试线程解锁 —— B 此刻才被允许重入临界区：队头匹配 —— 不查
 *     currentKey==null，currentKey=keyMain 被覆盖为 keyB。
 * 每一步的先后顺序由「谁持有内部锁」唯一决定，不存在调度自由度。
 *
 * 本测试在缺陷存在时必须失败（RED）；唤醒分支补上 currentKey==null 复查
 * （或等价修复）后应转绿。
 */
public class BusArbitrationDoubleHoldRedTest {

    @Test
    public void signalledWaiterOverwritesFastPathHolder_twoKeysBothTreatedAsHolder() throws Exception {
        final SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("PORT-UNDER-TEST", 9600, 8, 1, 0), 2, null);
        final ReentrantLock portLock = (ReentrantLock) TestTools.getPrivateField(port, "lock");

        // 线程A：无竞争快速路径取得 keyA
        final String[] keyA = new String[1];
        final CountDownLatch aDone = new CountDownLatch(1);
        final Throwable[] aError = new Throwable[1];
        start("holder-A", aDone, aError, () -> keyA[0] = port.acquire(2, TimeUnit.SECONDS));
        assertTrue("线程A的acquire必须在期限内返回", aDone.await(10, TimeUnit.SECONDS));
        assertNull("线程A不应抛异常", aError[0]);
        assertNotNull("无竞争下线程A必须取得锁", keyA[0]);

        // 线程B：入队并挂起，等待 release 的 signal 唤醒
        final String[] keyB = new String[1];
        final CountDownLatch bDone = new CountDownLatch(1);
        final Throwable[] bError = new Throwable[1];
        start("waiter-B", bDone, bError, () -> keyB[0] = port.acquire(2, TimeUnit.SECONDS));
        awaitWaiterParked(port, portLock, 1);

        portLock.lock();
        try {
            // 持锁窗口开始：B 在同步队列中，无法前行
            assertTrue("release(keyA) 必须成功", port.release(keyA[0]));
            // 重入快速路径：此刻 currentKey==null，测试线程扮演「窗口内抢到锁的竞争者」
            final String keyMain = port.acquire(2, TimeUnit.SECONDS);
            assertNotNull("持锁窗口内测试线程必须能快速路径取得锁（扮演竞争者）", keyMain);

            portLock.unlock(); // B 此刻才被允许重入临界区 —— 唤醒分支执行覆盖
            assertTrue("线程B的acquire必须在期限内返回", bDone.await(10, TimeUnit.SECONDS));
            assertNull("线程B不应抛异常", bError[0]);

            // ===== 以下断言在当前缺陷下失败（RED），修复后转绿 =====
            assertNull("双持有：竞争者（keyMain=" + keyMain + "）从未 release，被唤醒的等待者 B"
                    + " 不应被授予锁，实际返回 " + keyB[0], keyB[0]);
            assertEquals("currentKey 被唤醒分支无声覆盖，应仍是快速路径持有者的 key",
                    keyMain, currentKeyUnderLock(port, portLock));
            assertTrue("被覆盖的持有者必须仍能用自己的 key 释放锁（覆盖后其授权永久失效）",
                    port.release(keyMain));
        } finally {
            if (portLock.isHeldByCurrentThread()) {
                portLock.unlock();
            }
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

    private static String currentKeyUnderLock(Object owner, ReentrantLock portLock) throws Exception {
        portLock.lock();
        try {
            return (String) TestTools.getPrivateField(owner, "currentKey");
        } finally {
            portLock.unlock();
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
