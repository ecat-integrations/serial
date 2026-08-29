package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 【RED：Q-1/Q-2 二轮幽灵锁】F-12 红测为何没抓住：它模拟的是「IO 车道线程挂死但硬超时
 * 仍触发」——release 链最终执行，锁不残留。live wedge 的真实形态是 release 执行链整体丢失
 *（计时任务入队被拒/持有线程被上游无界等待吸收），currentKey 成永久幽灵锁：
 * 持锁线程已退出、永不 release，后续 acquire 永远超时，端口永久瘫痪
 *（live 实证：Acquire timeout 行报持锁 45min 的 1787527266233-28）。
 *
 * <p>契约：
 * <ul>
 *   <li>持锁时长超过收割阈值 → 下一次 acquire 入口按 release 同一状态机强制清零并放行；</li>
 *   <li>持锁时长未超阈值（可能仍是合法长事务）→ 不得收割，acquire 照常超时返回 null
 *       （36 号守卫上线后此用例的持锁者换线程——同线程双取已由守卫接管为立即抛，
 *       详见 {@code SerialSourcePortSameThreadNestedAcquireGuardTest}）。</li>
 * </ul>
 */
public class SerialSourcePortGhostLockReapTest {

    /** 阈值余量：收割判据是墙钟持锁时长，测试用 CountDownLatch 定时等待跨过阈值（非 sleep 猜测）。 */
    private static final long REAP_THRESHOLD_MS = 100;
    private static final long CROSS_THRESHOLD_WAIT_MS = 300;
    private static final long ACQUIRE_TIMEOUT_MS = 500;

    private SerialSourcePort newPort() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("GHOST-REAP-PORT", 9600, 8, 1, 0), 1, null);
        port.setGhostReapThresholdMsForTest(REAP_THRESHOLD_MS);
        return port;
    }

    /**
     * 幽灵锁形态：持锁 key 的 release 永久缺失（持锁调用已返回、无人再 release）。
     * 修复前：第二个 acquire 只能超时返回 null（红）；修复后：收割后立即可获得。
     */
    @Test
    public void acquireReapsGhostLock_whenHeldBeyondThreshold() throws Exception {
        SerialSourcePort port = newPort();
        String ghostKey = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("首次 acquire 应成功取得锁", ghostKey);

        // 模拟 release 链整体丢失：无人 release，等墙钟跨过收割阈值
        // 定时等待跨过收割阈值（latch 永不 countDown，await 到点返回 false 属预期，取墙钟推进语义）
        new CountDownLatch(1).await(CROSS_THRESHOLD_WAIT_MS, TimeUnit.MILLISECONDS);

        String revived = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull("幽灵锁应被收割，后续 acquire 应立即可得（修复前此处为 null = 红）", revived);

        // 收割后旧 key 已不在锁状态机上：release 应报告无效（证明状态确已清零，非双持有）
        assertFalse("幽灵 key 的迟到 release 应无效", port.release(ghostKey));
        assertTrue(port.release(revived));
    }

    /**
     * 契约：未超阈值（可能是合法长事务）不得收割——acquire 照常超时返回 null。
     * 36 号守卫上线后持锁者换线程：同线程双取已被守卫接管为立即抛（见
     * {@code sameThreadDoubleAcquire_belowThreshold_throwsImmediately} 与
     * {@code SerialSourcePortSameThreadNestedAcquireGuardTest}），本用例回归
     * 「异线程等锁、阈值内不收割」的原语义。
     */
    @Test
    public void ghostLockNotReaped_belowThreshold() throws Exception {
        // 阈值取秒级大窗（非类常量 100ms）：本用例的持锁者经线程跃迁取得，负向断言
        // 「锁仍被持有、不得收割」必须对并行构建下的调度噪声免疫——100ms 窗在线程唤醒
        // 延迟下可能被误推过阈值进入收割分支（假红）；收割边界两侧由 beyondThreshold
        // 用例与 36 号守卫顺序约束用例钉死，本用例只回归「持有时长 ≪ 阈值不收割」。
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("GHOST-REAP-PORT", 9600, 8, 1, 0), 1, null);
        port.setGhostReapThresholdMsForTest(5_000);
        final String[] heldKey = new String[1];
        final CountDownLatch holderDone = new CountDownLatch(1);
        final Throwable[] holderError = new Throwable[1];
        start("ghost-holder", holderDone, holderError,
                () -> heldKey[0] = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue("持锁线程的 acquire 必须在期限内返回", holderDone.await(10, TimeUnit.SECONDS));
        assertNull("持锁线程不应抛异常", holderError[0]);
        assertNotNull("持锁线程必须取得锁", heldKey[0]);

        // 不等阈值（持锁时长 ≪ 5s 收割阈值），异线程 acquire 应超时返回 null
        String blocked = port.acquire(50, TimeUnit.MILLISECONDS);
        assertNull("阈值内持锁是合法持有，不得收割", blocked);

        // 正常 release 后锁状态恢复，下一个 acquire 直接可得
        assertTrue(port.release(heldKey[0]));
        String next = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(next);
        port.release(next);
    }

    /**
     * 契约替换（36 号守卫）：本测试类原把「同线程双取 → 50ms 超时返 null」契约化——
     * 那是把自死锁 bug 行为写成契约（vaisala 事故 5h44m 静默空转的教训，用户已批准换约）。
     * 持锁未释放期间的同线程二次取锁现为立即抛（fail-fast，错误直达根因），锁状态不因
     * 抛出改变。收割阈值取秒级大窗：守卫在收割之后，两次取锁间若被调度噪声推过 100ms
     * 类常量阈值会先收割不抛（假红）。
     */
    @Test
    public void sameThreadDoubleAcquire_belowThreshold_throwsImmediatelyInsteadOfTimingOut() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("GHOST-REAP-PORT", 9600, 8, 1, 0), 1, null);
        port.setGhostReapThresholdMsForTest(5_000);
        String heldKey = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(heldKey);

        try {
            port.acquire(50, TimeUnit.MILLISECONDS);
            fail("同线程二次 acquire 应立即抛（旧形态：阻塞 50ms 后返 null 的自死锁）");
        } catch (IllegalStateException expected) {
            // 契约：fail-fast 而非静默超时
        }
        assertTrue("守卫抛出不得改变锁状态——原 key release 仍有效", port.release(heldKey));
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
