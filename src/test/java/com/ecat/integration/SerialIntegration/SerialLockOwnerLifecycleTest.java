package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ecat.core.CommTrace.ResourceOwner;

/**
 * 事务锁 owner 生命周期（io-resource-owner 设计 §8 行 1）：lockAcquireOwner 是
 * 「当前住户」权威——acquire/acquirePollingBounded 授予登记（快路径与锁忙等待授予
 * 两授予点）、release 与幽灵锁收割两路径清除、同线程嵌套 fail-fast 路径不得误挂
 * （守卫在授予之前抛，锁状态不动）。
 *
 * <p>幽灵收割用既有 GhostLockReapTest 同范式：真实墙钟 + CountDownLatch 有界等待
 * 跨过缩短后的收割阈值（收割判据本身是墙钟持锁时长，属测试缝时钟无法替代的既有语义）。
 */
public class SerialLockOwnerLifecycleTest {

    private static final long REAP_THRESHOLD_MS = 100;
    private static final long CROSS_THRESHOLD_WAIT_MS = 300;

    private static ResourceOwner deviceOwner(String deviceId) {
        return ResourceOwner.device("com.ecat:integration-serial", "entry-1", deviceId);
    }

    private SerialSourcePort newPort() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("LOCK-OWNER-PORT", 9600, 8, 1, 0), 1, null);
        port.setGhostReapThresholdMsForTest(REAP_THRESHOLD_MS);
        return port;
    }

    /** acquire 登记 owner → release 清除；不清则 TX/RX 归因会挂在已结束事务的设备上。 */
    @Test
    public void acquireRegistersOwnerAndReleaseClearsIt() {
        SerialSourcePort port = newPort();
        ResourceOwner owner = deviceOwner("dev-1");

        String key = port.acquire(owner);
        assertNotNull(key);
        assertEquals("授予点登记 lockAcquireOwner", owner, port.getLockAcquireOwner());

        assertTrue(port.release(key));
        assertNull("release 须清除 lockAcquireOwner", port.getLockAcquireOwner());
    }

    /** acquirePollingBounded 快路径（轮询入口）同样登记 owner，release 清除。 */
    @Test
    public void pollingAcquireFastPathRegistersOwner() throws Exception {
        SerialSourcePort port = newPort();
        ResourceOwner owner = deviceOwner("dev-2");

        String key = port.acquirePollingBounded(owner, 500).get(5, TimeUnit.SECONDS);
        assertNotNull(key);
        assertEquals(owner, port.getLockAcquireOwner());

        assertTrue(port.release(key));
        assertNull(port.getLockAcquireOwner());
    }

    /**
     * 锁忙等待授予点（IO 旁池线程上排队等锁）同样登记 owner：等待者经 waitQueue 队头
     * 接管后，lockAcquireOwner 是等待轮次的 owner 而非 null——多设备同口的归因不因
     * 等待路径断链。持锁者用辅助线程（测试线程自持时快路径是同线程嵌套守卫的
     * fail-fast 形态，无法构造「锁忙等待」前置）。
     */
    @Test
    public void pollingAcquireWaitGrantRegistersOwner() throws Exception {
        SerialSourcePort port = newPort();
        ResourceOwner holderOwner = deviceOwner("holder-dev");
        ResourceOwner waiterOwner = deviceOwner("waiter-dev");

        final String[] heldKey = new String[1];
        final CountDownLatch holderDone = new CountDownLatch(1);
        final Throwable[] holderError = new Throwable[1];
        Thread holderThread = new Thread(() -> {
            try {
                heldKey[0] = port.acquire(holderOwner);
            } catch (Throwable x) {
                holderError[0] = x;
            } finally {
                holderDone.countDown();
            }
        }, "owner-lifecycle-holder");
        holderThread.setDaemon(true);
        holderThread.start();
        assertTrue("持锁辅助线程必须在期限内取得锁", holderDone.await(5, TimeUnit.SECONDS));
        assertNull("持锁辅助线程不应抛异常", holderError[0]);
        assertNotNull(heldKey[0]);

        CompletableFuture<String> granted = port.acquirePollingBounded(waiterOwner, 5_000);
        awaitWaitingCount(port, 1);

        assertTrue(port.release(heldKey[0]));
        String key = granted.get(5, TimeUnit.SECONDS);
        assertNotNull(key);
        assertEquals("等待授予点同样登记等待方 owner", waiterOwner, port.getLockAcquireOwner());

        assertTrue(port.release(key));
        assertNull(port.getLockAcquireOwner());
    }

    /** 等待者入队事件等待（deadline 轮询：旁池异步入队，立即读数是竞态）。 */
    private static void awaitWaitingCount(SerialSourcePort port, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.getWaitingCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待者未在 5s 内入队: expected>=" + expected
                        + ", actual=" + port.getWaitingCount());
            }
        }
    }

    /**
     * 幽灵锁收割须一并清 owner（收割=按 release 同一状态机清零）：收割后旧 owner 不残留，
     * 新授予登记新 owner。若收割漏清 owner，收割口的迟到字节会误挂到已死事务的设备。
     */
    @Test
    public void ghostReapClearsOwnerNotStickingToNextGrant() throws Exception {
        SerialSourcePort port = newPort();
        ResourceOwner ghostOwner = deviceOwner("ghost-dev");
        ResourceOwner nextOwner = deviceOwner("next-dev");

        String ghostKey = port.acquire(500, TimeUnit.MILLISECONDS, ghostOwner);
        assertNotNull(ghostKey);
        assertEquals(ghostOwner, port.getLockAcquireOwner());

        // release 链整体丢失（无人 release），等墙钟跨过收割阈值
        new CountDownLatch(1).await(CROSS_THRESHOLD_WAIT_MS, TimeUnit.MILLISECONDS);

        String revived = port.acquire(500, TimeUnit.MILLISECONDS, nextOwner);
        assertNotNull("幽灵锁应被收割，后续 acquire 立即可得", revived);
        assertEquals("收割清零后新授予登记新 owner（旧 owner 不残留）",
                nextOwner, port.getLockAcquireOwner());
        assertFalse("幽灵 key 的迟到 release 无效（状态确已清零）", port.release(ghostKey));
        assertTrue(port.release(revived));
    }

    /**
     * 同线程嵌套 fail-fast（vaisala 守卫）在授予之前抛——嵌套方的 owner 不得误挂，
     * 既有持有关系（第一个 owner）不受影响。
     */
    @Test
    public void sameThreadNestedAcquireFailFastDoesNotAttachNestedOwner() {
        SerialSourcePort port = newPort();
        ResourceOwner first = deviceOwner("first-dev");
        ResourceOwner nested = deviceOwner("nested-dev");

        String key = port.acquire(first);
        assertNotNull(key);

        try {
            port.acquire(nested);
            fail("同线程嵌套取锁应立即抛（fail-fast 守卫）");
        } catch (IllegalStateException expected) {
            // 守卫契约：锁状态原样不动
        }
        assertSame("嵌套路径不得改写 lockAcquireOwner", first, port.getLockAcquireOwner());

        assertTrue(port.release(key));
        assertNull(port.getLockAcquireOwner());
    }
}
