package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 「至多一个授予者」结果级不变量测试（test-migration-spec-e1-e4 §1.3/§6 决策 A：
 * E1 竞态类编排退役、不变量存续——BusArbitrationDoubleHoldRedTest 的换代副本，两者并存）。
 *
 * <p>锚点=只读观测缝（{@link SerialSourcePort#getQueueDepth()}/
 * {@link SerialSourcePort#hasInFlight()}/授予-完成计数对），<b>零反射</b>——
 * 与 E1 旧类的反射锁编排（钉死唤醒窗口的确定性手段）不同，本类只断言结果级不变量：
 *
 * <ul>
 *   <li>任意采样时刻 {@code granted - completed ∈ {0,1}}（至多一个授予未完成）；</li>
 *   <li>排队等待者在持有者释放前不得被授予（不越序）；</li>
 *   <li>全部事务结束后计数对收敛（granted==completed）、深度归零、无在飞。</li>
 * </ul>
 *
 * <p><b>保护力度声明（如实，不粉饰为等价）</b>：旧 E1 用可重入锁编排确定性构造缺陷
 * 中间态；本类是概率性采样+终态断言，若实现出现「移交与计数刷新非原子」的罕见竞态，
 * 采样可能错过中间态（spec §1.4 已登记该减弱）。缓解：计数对以内部状态迁移为真源
 * （granted=txGeneration；completed 与清零同临界块），撕裂读按「completed 先读-后读
 * 夹逼 granted」范式过滤（见 {@link #assertPairInvariant(SerialSourcePort)}）。
 *
 * <p>当前锁面实现下绿 = 观测缝语义自证（C1 判据）；C2 命令队列化后同一断言组
 * 锚命令队列语义，无需改写。
 */
public class BusArbitrationAtMostOneGranteeTest {

    /** 并发事务线程数（竞争在容量内，全部应成功而非被拒）。 */
    private static final int WORKERS = 4;
    /** 每线程事务轮数。 */
    private static final int ROUNDS = 25;
    /** 单笔事务的等锁预算（竞争激烈时的余量；不得超时返 null）。 */
    private static final long ACQUIRE_BUDGET_MS = 30_000;

    private SerialSourcePort newPort() {
        return new SerialSourcePort(new SerialInfo("GRANTEE-INV-PORT", 9600, 8, 1, 0), WORKERS + 1, null);
    }

    /**
     * 不变量主证：多线程并发 acquire/release 事务流下，任意采样时刻授予-完成计数对
     * 不越出 {0,1}；事务流结束后计数对收敛、深度归零、无在飞。
     */
    @Test
    public void concurrentTransactionFlowKeepsAtMostOneOutstandingGrant() throws Exception {
        final SerialSourcePort port = newPort();
        final CountDownLatch workersDone = new CountDownLatch(WORKERS);
        final Throwable[] workerError = new Throwable[WORKERS];

        for (int i = 0; i < WORKERS; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    for (int r = 0; r < ROUNDS; r++) {
                        String key = acquireWithRetry(port);
                        assertTrue("事务结束必须能用自己的 key 释放（授权不可被窃取）", port.release(key));
                    }
                } catch (Throwable x) {
                    workerError[idx] = x;
                } finally {
                    workersDone.countDown();
                }
            }, "grantee-worker-" + i);
            t.setDaemon(true);
            t.start();
        }

        // 采样器：事务流进行中持续校验计数对不变量（deadline 轮询，无 sleep 猜测）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (workersDone.getCount() > 0) {
            assertPairInvariant(port);
            if (System.nanoTime() > deadline) {
                throw new AssertionError("事务线程未在 120s 内完成（剩 " + workersDone.getCount() + "）");
            }
            Thread.yield();
        }
        assertTrue("事务线程必须在期限内完成", workersDone.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < WORKERS; i++) {
            assertNull("事务线程 " + i + " 不应抛异常", workerError[i]);
        }

        // 终态收敛：全部授予都有对应完成，队列无残留、无在飞
        assertEquals("结束后授予-完成计数对必须收敛", port.getGrantedCount(), port.getCompletedCount());
        assertEquals("结束后队列深度必须归零（无泄漏）", 0, port.getQueueDepth());
        assertFalse("结束后不得有在飞命令", port.hasInFlight());
    }

    /**
     * 不变量的确定性面：持有者释放前，排队等待者不得被授予（不越序、不双授予）——
     * 锁面下由「currentKey 非空则无新授予」结构性保证，断言无需计时猜测。
     */
    @Test
    public void queuedWaiterNotGrantedBeforeHolderReleases() throws Exception {
        final SerialSourcePort port = newPort();
        final String held = acquireOnHelperThread(port);

        final String[] keyB = new String[1];
        final CountDownLatch bDone = new CountDownLatch(1);
        final Throwable[] bError = new Throwable[1];
        Thread waiter = new Thread(() -> {
            try {
                keyB[0] = port.acquire(ACQUIRE_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable x) {
                bError[0] = x;
            } finally {
                bDone.countDown();
            }
        }, "grantee-waiter");
        waiter.setDaemon(true);
        waiter.start();
        awaitQueueDepth(port, 1);
        assertEquals("等待者入队后深度应为 1（不含在飞）", 1, port.getQueueDepth());
        assertTrue("在飞观测应见持有者", port.hasInFlight());
        // 持有者未释放：B 不可能被授予（确定性——锁面无越序授予路径）
        assertTrue("持有者释放前等待者不得返回", bDone.getCount() == 1);

        assertTrue(port.release(held));
        assertTrue("等待者必须在期限内被授予", bDone.await(10, TimeUnit.SECONDS));
        assertNull("等待者不应抛异常", bError[0]);
        assertNotNull("释放后队头等待者必须被授予", keyB[0]);
        assertTrue("等待者的授予必须可释放", port.release(keyB[0]));

        assertEquals("两笔授予两笔完成，计数对收敛", port.getGrantedCount(), port.getCompletedCount());
        assertEquals(0, port.getQueueDepth());
        assertFalse(port.hasInFlight());
    }

    /**
     * 按生产语义取锁（锁面下 null 不等于缺陷）：acquire 返 null 的一类合法形态是
     * 「唤醒后锁被快速路径竞争者抢占（handed-off race 失败，按未取得总线重试）」——
     * 本测试只守「至多一个授予者」不变量，越序竞争输家按生产语义在总预算内重试。
     */
    private static String acquireWithRetry(SerialSourcePort port) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACQUIRE_BUDGET_MS);
        while (true) {
            String key = port.acquire(2, TimeUnit.SECONDS);
            if (key != null) {
                return key;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("总预算 " + ACQUIRE_BUDGET_MS + "ms 内未取得锁（竞争持续输家）");
            }
        }
    }

    /**
     * 计数对不变量采样（撕裂容忍）：granted/completed 是两次独立读，读 granted 前后
     * 恰有完成发生会使 granted-c1 假性为 2——以第二次 completed 读数夹逼：若
     * g-c2 已回落到 {0,1}，说明差值由期间的真实完成解释，非双授予。
     */
    private static void assertPairInvariant(SerialSourcePort port) {
        long c1 = port.getCompletedCount();
        long g = port.getGrantedCount();
        long c2 = port.getCompletedCount();
        assertTrue("完成数不得超过授予数（完成必先有授予）: g=" + g + ", c2=" + c2, g >= c2);
        if (g - c1 > 1) {
            assertTrue("granted-completed 越出 {0,1}（g=" + g + ", c1=" + c1 + ", c2=" + c2
                            + "）——疑似双授予/授予无对应完成路径",
                    g - c2 <= 1);
        }
    }

    /** 等待命令入队（deadline 轮询事件已发生才继续，超时硬失败——不睡固定时间猜）。 */
    private static void awaitQueueDepth(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (port.getQueueDepth() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("命令未在 10s 内入队: expected>=" + expected
                        + ", actual=" + port.getQueueDepth());
            }
            Thread.yield();
        }
    }

    /**
     * 异线程持锁构造（「锁忙」用例的标准前置：测试线程自持再取是自死锁形态，守卫抛）：
     * 辅助线程取得后退出（锁状态在端口对象上，与持锁线程存活无关），释放由测试线程按 key 执行。
     */
    private static String acquireOnHelperThread(SerialSourcePort port) throws Exception {
        final String[] held = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        Thread holder = new Thread(() -> {
            try {
                held[0] = port.acquire(10, TimeUnit.SECONDS);
            } catch (Throwable x) {
                error[0] = x;
            } finally {
                done.countDown();
            }
        }, "grantee-test-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("持锁辅助线程必须在期限内返回", done.await(10, TimeUnit.SECONDS));
        assertNull("持锁辅助线程不应抛异常", error[0]);
        assertNotNull("持锁辅助线程必须取得锁", held[0]);
        return held[0];
    }
}
