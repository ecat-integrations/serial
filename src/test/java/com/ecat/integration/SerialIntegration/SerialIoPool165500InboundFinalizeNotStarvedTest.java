package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 165500 守护回归（bugs/fixed/bug-record-20260826-165500，29 号 v2 S1 必写专项）：
 * 写压测下（写事务占口期间）同口入站 finalize 不被饿死。
 *
 * <p><b>事故形态（165500 现场机制）</b>：写事务的 permit 有界等待发生在车道 worker 上
 * （等待侧占口 8s）→ 同车道排在其后的入站响应 finalize 永不上道 → 健康口被写拖成
 * transaction-hard-timeout 强拆（TX-ABORTED + WEDGE-RECOVERY 级联）。
 *
 * <p><b>本域池形态下的守护不变量</b>：
 * <ul>
 *   <li>同口 FIFO 公平性——finalize 入队后的等待只由「排在其前」的任务决定，其后持续
 *       到来的写压测任务永远排在它后面（不跳跃、不插队），写流量无论多密都不能把
 *       finalize 饿死；</li>
 *   <li>占口任务只有有界 IO（写发送/缓冲读），无任何「等待侧占口」形态驻留口内——
 *       写事务占口释放后 finalize 立即获得执行（同一 drain 循环续跑，无需新池提交）。</li>
 * </ul>
 *
 * <p>同步纪律：手动驱动 drain（测试线程驱动捕获的 drain 提交）+ latch 有界等待，
 * 全程零 Thread.sleep、零真实时钟依赖。</p>
 */
public class SerialIoPool165500InboundFinalizeNotStarvedTest {

    private SerialIoPoolTest.ManualPoolSeam seam;
    private Executor portView;
    private final List<String> order = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() {
        SerialIoPool.resetForTest();
        seam = new SerialIoPoolTest.ManualPoolSeam();
        SerialIoPool.bindForTest(seam);
        portView = SerialIoPool.executorFor("ttyUSB161-guard165500");
    }

    @After
    public void tearDown() {
        SerialIoPool.unbindForTest();
        SerialIoPool.resetForTest();
    }

    /**
     * 红测核心：写事务占口期间（首写在飞、其后写压测持续入队），入站 finalize 排在
     * 写压测流中间——首写释放后 finalize 必须先于其后的全部压测写执行（FIFO 公平，
     * 不被写流饿死），且在既有 drain 循环内完成（无新池提交、无额外等待层）。
     */
    @Test(timeout = 15000)
    public void inboundFinalizeNotStarvedBySustainedWritePressure() throws Exception {
        final int writeStormSize = 20;   // finalize 身后的持续写压测量
        CountDownLatch writeTxEntered = new CountDownLatch(1);
        CountDownLatch writeTxRelease = new CountDownLatch(1);
        CountDownLatch finalizeDone = new CountDownLatch(1);

        // 1) 写事务占口：首写任务进入后驻留（165500 的「写事务占口期间」形态——有界 IO
        //    完成前的在飞窗，测试以 latch 精确控制其时长，不 sleep）
        portView.execute(() -> {
            order.add("write-tx");
            writeTxEntered.countDown();
            try {
                writeTxRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread drainDriver = new Thread(seam::runLastSubmission, "drain-driver-165500");
        drainDriver.start();
        assertTrue("写事务必须已占口执行", writeTxEntered.await(5, TimeUnit.SECONDS));

        // 2) 写压测持续：finalize 之前再压一批写在它前面（真实压测形态：finalize 入队时
        //    口上已有排队写），finalize 之后再压一大批（持续写流，饿死候选源）
        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            portView.execute(() -> order.add("write-before-" + idx));
        }
        portView.execute(() -> {
            order.add("inbound-finalize");
            finalizeDone.countDown();
        });
        for (int i = 1; i <= writeStormSize; i++) {
            final int seq = i;
            portView.execute(() -> order.add("write-storm-" + seq));
        }
        // 单飞断言：整个压测流只并入既有 drain（165500 的「排在其后永不上道」反例面：
        // 这里证明的是压测流全部并入同一 FIFO，不产生额外排队层）
        assertEquals("写压测流必须并入既有 drain（同口单飞，不另起池任务）",
                1, seam.drainSubmissions.size());

        // 3) 占口期间：finalize 必然未执行（FIFO 在飞事实，确定性非竞态）
        assertFalse("写事务占口期间 finalize 不得越过 FIFO（顺序性前提）", finalizeDone.getCount() == 0);

        // 4) 写事务释放（IO 完成）：finalize 必须在有限等待内完成——其后 20 个压测写
        //    全部排在它后面，任何一个先执行都是饿死/乱序红
        writeTxRelease.countDown();
        assertTrue("写占口释放后 finalize 必须立即获得执行（不被身后写压测饿死）",
                finalizeDone.await(5, TimeUnit.SECONDS));
        drainDriver.join(5000);

        // 5) 顺序断言：finalize 严格先于全部 storm 写；它之前的 3 个写+首写在它之前
        int finalizeIndex = order.indexOf("inbound-finalize");
        assertTrue("finalize 必须已执行且入序", finalizeIndex >= 0);
        for (int i = 1; i <= writeStormSize; i++) {
            assertTrue("压测写 write-storm-" + i + " 不得先于 finalize 执行（FIFO 公平=不饿死）",
                    order.indexOf("write-storm-" + i) > finalizeIndex);
        }
        for (int i = 1; i <= 3; i++) {
            assertTrue("finalize 前置写必须在 finalize 之前（同口 FIFO 无乱序）",
                    order.indexOf("write-before-" + i) < finalizeIndex);
        }
    }

    /**
     * 持续写压测下的到点保障（现实时间形态补充）：写流连续不断地进入同口（每写完成即
     * 下一写已入队、口永不清空），中途入队的 finalize 仍必须完成——FIFO 使其等待上界
     * = 入队时刻前排队的写数 × 单写时长，与其身后无限到来的写无关。
     */
    @Test(timeout = 15000)
    public void finalizeCompletesUnderNeverDrainingWriteStream() throws Exception {
        CountDownLatch finalizeDone = new CountDownLatch(1);
        final int writesAhead = 5;      // finalize 身前的有界写量
        final int writesBehind = 10;    // finalize 身后的持续流

        for (int i = 1; i <= writesAhead; i++) {
            final int idx = i;
            portView.execute(() -> order.add("write-ahead-" + idx));
        }
        portView.execute(() -> {
            order.add("inbound-finalize");
            finalizeDone.countDown();
        });
        for (int i = 1; i <= writesBehind; i++) {
            final int idx = i;
            portView.execute(() -> order.add("write-behind-" + idx));
        }

        // 手动驱动 drain：循环会一直消化到队列空（身前写+finalize+身后写全部入序），
        // finalize 完成即证「口内持续有写也不饿死」
        seam.runLastSubmission();

        assertTrue("持续写流下 finalize 必须完成（等待上界=身前写量，与身后写无关）",
                finalizeDone.await(1, TimeUnit.MILLISECONDS) && finalizeDone.getCount() == 0);
        int finalizeIndex = order.indexOf("inbound-finalize");
        assertEquals("同口全序：身前写在 finalize 前", writesAhead, countBefore(order, finalizeIndex, "write-ahead-"));
        assertEquals("同口全序：身后写在 finalize 后", writesBehind, countAfter(order, finalizeIndex, "write-behind-"));
    }

    private static int countBefore(List<String> order, int index, String prefix) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (order.get(i).startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static int countAfter(List<String> order, int index, String prefix) {
        int count = 0;
        for (int i = index + 1; i < order.size(); i++) {
            if (order.get(i).startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}
