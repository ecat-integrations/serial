package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
 *   <li>持锁时长未超阈值（可能仍是合法长事务）→ 不得收割，acquire 照常超时返回 null。</li>
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

    /** 契约：未超阈值（可能是合法长事务）不得收割——acquire 照常超时返回 null。 */
    @Test
    public void ghostLockNotReaped_belowThreshold() throws Exception {
        SerialSourcePort port = newPort();
        String heldKey = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(heldKey);

        // 不等阈值（持锁远小于 100ms 收割阈值），第二个 acquire 应超时返回 null
        String blocked = port.acquire(50, TimeUnit.MILLISECONDS);
        assertNull("阈值内持锁是合法持有，不得收割", blocked);

        // 正常 release 后锁状态恢复，下一个 acquire 直接可得
        assertTrue(port.release(heldKey));
        String next = port.acquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(next);
        port.release(next);
    }
}
