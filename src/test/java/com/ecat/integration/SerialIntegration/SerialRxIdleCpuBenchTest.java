package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * 零空转实证基准（RX 推模型验收 §7.1）：N 口真实 PTY 空闲挂读线程，ThreadMXBean 采样
 * 全部 serial-rx-* 线程 CPU 时间总和——与旧 25ms 轮询（48 口 1920 次/s 系统调用、每口
 * 40 次/s 主动醒）对照的结构性钉子。阻塞读睡在内核 poll，仅每 readTimeout 醒一次
 * （2 次/s/口），单次醒开销微秒级——本基准钉「单次醒开销上限」与「线程零失控」，
 * 不是绝对 0（实测 8 口 60s 合计 33ms ≈ 34µs/醒；设计文档「CPU=0」是 3 秒窗单口
 * 采样粒度假象，按实测校准）。
 *
 * <p>断言推导：唤醒频率上界 = IDLE 秒数 / readTimeout(500ms) 每口；单次醒开销上界
 * 100µs（实测 34µs 的 ~3 倍余量，吸收 JIT 冷启动波动）→ CPU 总和上界
 * = PORT_COUNT × (IDLE 秒数×2) × 100µs。跑法：环境变量门 {@code SERIAL_BENCH=1}
 * 拉起（未设时 Assume 跳过，日常构建零成本），输出
 * {@code BENCH|idle-cpu|ports=N|threads=N|idleSec=60|cpuMs=X} 行供对照汇总。
 *
 * <p>真实 PTY 由 socat 进程对提供（用毕按 PID destroy 回收 + 临时目录递归清理）；
 * socat 不可用则跳过。
 *
 * @author coffee
 */
public class SerialRxIdleCpuBenchTest {

    private static final int PORT_COUNT = 8;
    private static final long IDLE_SAMPLE_SECONDS = 60;
    private static final String RX_THREAD_PREFIX = "serial-rx-";
    /** 单次超时醒开销上界（µs）：实测 ~34µs/醒的 3 倍余量。 */
    private static final long WAKE_BUDGET_MICROS = 100;
    /** 读超时档（毫秒）：与生产默认 Const.READ_TIMEOUT_MS 同档。 */
    private static final long READ_TIMEOUT_MS = 500;

    private final List<Process> socats = new ArrayList<>();
    private final List<File> tempDirs = new ArrayList<>();

    @After
    public void tearDown() {
        for (Process socat : socats) {
            socat.destroy();
        }
        socats.clear();
        for (File dir : tempDirs) {
            deleteRecursively(dir);
        }
        tempDirs.clear();
    }

    @Test(timeout = 180_000)
    public void idlePorts_rxThreadsConsumeZeroCpu() throws Exception {
        Assume.assumeTrue("SERIAL_BENCH=1 时才执行（日常构建跳过）", System.getenv("SERIAL_BENCH") != null);

        // N 口真实 PTY + 每口一条推模型读线程（空闲，无任何数据注入）
        List<JSerialCommTransport> transports = new ArrayList<>();
        try {
            List<File> ptyLinks = new ArrayList<>();
            for (int i = 0; i < PORT_COUNT; i++) {
                File dir = File.createTempFile("serial-idle-bench-" + i, "");
                assertTrue(dir.delete() && dir.mkdir());
                tempDirs.add(dir);
                File ptyA = new File(dir, "ptyA");
                File ptyB = new File(dir, "ptyB");
                ptyLinks.add(ptyA);
                socats.add(new ProcessBuilder("socat", "-d", "-d",
                        "pty,raw,echo=0,link=" + ptyA.getAbsolutePath(),
                        "pty,raw,echo=0,link=" + ptyB.getAbsolutePath())
                        .redirectErrorStream(true).start());
            }
            long linkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (File ptyA : ptyLinks) {
                while (!ptyA.exists()) {
                    assertTrue("PTY link 未在预算内就绪", System.nanoTime() < linkDeadline);
                    Thread.sleep(10);
                }
            }
            for (File ptyA : ptyLinks) {
                SerialSourcePort port = new SerialSourcePort(
                        new SerialInfo(ptyA.getAbsolutePath(), 9600, 8, 1, 0, 0, 500), 3, null);
                JSerialCommTransport transport = (JSerialCommTransport) port.transport();
                transport.onTransportDead(reason -> { });
                assertTrue("PTY 打开必须成功: " + ptyA, transport.open());
                transport.startReading((buf, len) -> { });
                transports.add(transport);
            }
            awaitRxThreadsUp(PORT_COUNT);

            // 60 秒空闲采样：全部 serial-rx-* 线程 CPU 时间总和（ThreadMXBean）
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            long cpuBefore = rxThreadCpuNanos(threads);
            Thread.sleep(TimeUnit.SECONDS.toMillis(IDLE_SAMPLE_SECONDS));
            long aliveAfter = countRxThreads();
            long cpuNanos = rxThreadCpuNanos(threads) - cpuBefore;
            assertEquals("全部读线程必须存活（零死亡零重启）", PORT_COUNT, aliveAfter);

            long cpuMs = TimeUnit.NANOSECONDS.toMillis(cpuNanos);
            long wakeCount = PORT_COUNT * (IDLE_SAMPLE_SECONDS * 1000L / READ_TIMEOUT_MS);
            long cpuBudgetMs = TimeUnit.MICROSECONDS.toMillis(wakeCount * WAKE_BUDGET_MICROS);
            System.out.println("BENCH|idle-cpu|ports=" + PORT_COUNT + "|threads=" + aliveAfter
                    + "|idleSec=" + IDLE_SAMPLE_SECONDS + "|cpuMs=" + cpuMs
                    + "|budgetMs=" + cpuBudgetMs);
            assertTrue("零空转契约：空闲 " + IDLE_SAMPLE_SECONDS + "s 读线程 CPU 总和 " + cpuMs
                    + "ms 须在上界 " + cpuBudgetMs + "ms 内（超界即存在轮询空转或线程失控）",
                    cpuMs <= cpuBudgetMs);
        } finally {
            for (JSerialCommTransport transport : transports) {
                transport.stopReading();
            }
        }
    }

    /** 等全部读线程就位（线程名计数，5s 有界）。 */
    private static void awaitRxThreadsUp(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (countRxThreads() < expected) {
            if (System.nanoTime() >= deadline) {
                fail("读线程未在预算内全部就位: " + countRxThreads() + "/" + expected);
            }
            Thread.sleep(10);
        }
    }

    /** 存活 serial-rx-* 线程的 CPU 纳秒总和。 */
    private static long rxThreadCpuNanos(ThreadMXBean threads) {
        long total = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith(RX_THREAD_PREFIX) && t.isAlive()) {
                total += threads.getThreadCpuTime(t.getId());
            }
        }
        return total;
    }

    private static int countRxThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith(RX_THREAD_PREFIX) && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /** 临时目录递归清理（基准产物自回收）。 */
    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
