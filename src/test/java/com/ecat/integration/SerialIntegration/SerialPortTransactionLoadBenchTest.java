package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

/**
 * 事务吞吐与 p99 负载基线对拍（验收性能对照数据源）：虚拟口 + 回声型模拟设备，
 * 走完整执行者对话生命周期（executeWithLambda 入队 → 写物理口 → RX 回声事件 →
 * 监听器完成事务），用 System.nanoTime 分段测每口事务吞吐与单事务时延分位。
 *
 * <p><b>为什么存在</b>：锁体系→每口串行执行者替换（有界优先级队列模型）这类仲裁层
 * 重写，正确性测试锁不住「调度开销是否退化」——需要同负载前后对照。本类只产出
 * 原始测量数据（吞吐 tx/s、p50/p99/max 时延），断言仅锁「完成数=N 且零异常零队满」，
 * <b>不把绝对性能写进断言</b>——绝对值随机器负载漂移，对比靠两次运行的数据而非阈值。
 *
 * <p><b>对话协议</b>：命令帧 {@code [0x02, seqHi, seqLo, 0x03]}，应答帧
 * {@code [0x02, seqHi, seqLo, 0x06, 0x03]}`。回声设备 = mock 口 writeBytes 回调内
 * 经 {@link SerialSourcePort#handleIncomingData} 注入应答（事件化到口执行线程，
 * OnBytes 在对话进行中即时分发——执行者契约保证），监听器按 seq 匹配完成事务。
 *
 * <p><b>跑法</b>：环境变量门 {@code SERIAL_BENCH=1} 拉起（未设时 Assume 跳过，
 * 日常构建零成本不进断言面——@Ignore 拉不起来，环境变量由 fork JVM 原生继承，
 * 不受 mvnd/surefire 系统属性传递坑影响）：
 * <pre>
 *   SERIAL_BENCH=1 mvnd test -Dtest=SerialPortTransactionLoadBenchTest
 * </pre>
 * 可选 {@code SERIAL_BENCH_DELAY_MS=&lt;毫秒&gt;} 注入模拟设备应答延迟（设备侧语义：
 * 收到命令后处理 N 毫秒才回应答——真实工况形态。注入走 serial 域既有确定性调度缝
 * {@link SerialSdkTimers#fireAfter}，与生产延迟命令同源；两侧对拍同等注入即对照有效）。
 * 零延迟形态用于放大观察仲裁层固定开销；带延迟形态用于测执行者开销占端到端时延比例。
 * 输出行以 {@code BENCH|} 前缀打印（group/run/tx/thr/p50/p99/max/delay），便于对照汇总。
 * 基准前每组先跑预热轮（预热数据弃置不计），对拍按同命令多次运行取中位。
 *
 * <p>同步纪律：全链 future/latch 有界等待，无 Thread.sleep；用例间独立端口名隔离。
 *
 * @author coffee
 */
public class SerialPortTransactionLoadBenchTest {

    /** 帧定界符 */
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte ACK = 0x06;

    /** 单口连续对话数（正式计测） */
    private static final int SERIAL_DIALOGUES = 500;
    /** 并发组：设备视图数与每视图对话数（3×100=300 场） */
    private static final int CONCURRENT_DEVICES = 3;
    private static final int DIALOGUES_PER_DEVICE = 100;
    /** 预热轮对话数（每会话，数据弃置——JIT/执行者线程热身后才计测） */
    private static final int WARMUP_PER_SESSION = 50;

    private static final long WRITE_ACK_WAIT_S = 5;
    private static final long DIALOGUE_WAIT_S = 10;

    /**
     * 模拟设备应答延迟（毫秒，环境变量 SERIAL_BENCH_DELAY_MS，缺省 0=即时回声）。
     * 设备侧语义：收到命令后「处理」N 毫秒才回应答。严格模式：非法值如实炸出，
     * 不静默归零——对照实验的负载参数错了比不跑更糟。
     */
    private static final long DEVICE_DELAY_MS = resolveDeviceDelayMs();

    private static long resolveDeviceDelayMs() {
        String v = System.getenv("SERIAL_BENCH_DELAY_MS");
        if (v == null || v.isEmpty()) {
            return 0L;
        }
        long parsed = Long.parseLong(v);
        if (parsed < 0) {
            throw new IllegalArgumentException("SERIAL_BENCH_DELAY_MS 不能为负: " + v);
        }
        return parsed;
    }

    /** 一次基准的测量产出：总场数、总耗时、每场时延（纳秒，提交点→事务完成） */
    static final class BenchResult {
        final String group;
        final int dialogues;
        final long totalNanos;
        final long[] latencyNanos;

        BenchResult(String group, int dialogues, long totalNanos, long[] latencyNanos) {
            this.group = group;
            this.dialogues = dialogues;
            this.totalNanos = totalNanos;
            this.latencyNanos = latencyNanos;
        }

        double throughputPerSec() {
            return dialogues / (totalNanos / 1_000_000_000.0d);
        }

        long percentileNanos(double p) {
            long[] sorted = latencyNanos.clone();
            Arrays.sort(sorted);
            int idx = (int) Math.ceil(p * sorted.length) - 1;
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= sorted.length) {
                idx = sorted.length - 1;
            }
            return sorted[idx];
        }

        /** BENCH| 统一行：对照组可 grep 直引，数字由程序产出不经手工换算 */
        String benchLine() {
            return String.format(Locale.ROOT,
                    "BENCH|group=%s|delay=%dms|tx=%d|thr=%.1f|p50=%.3f|p99=%.3f|max=%.3f",
                    group, DEVICE_DELAY_MS, dialogues, throughputPerSec(),
                    percentileNanos(0.50) / 1_000_000.0d,
                    percentileNanos(0.99) / 1_000_000.0d,
                    percentileNanos(1.0) / 1_000_000.0d);
        }

        /**
         * 落盘到 target/bench-results.txt（追加一行）：surefire/mvnd 下 stdout 去向
         * 不稳定（-q 吞控制台、报告 system-out 不落），文件是唯一可靠采集面；
         * target/ 为构建产物目录，多次运行按行累积，对拍汇总后随 clean 回收。
         */
        void appendToFile() {
            java.io.File out = new java.io.File("target/bench-results.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(out, true)) {
                w.write(benchLine() + "\n");
            } catch (java.io.IOException e) {
                throw new AssertionError("基准结果落盘失败（采集面必须可用）", e);
            }
        }
    }

    // ========== 组①：单口连续对话（串行基准） ==========

    /**
     * 单口连续 N 场对话（写命令→收应答）：提交与等待在同一调用线程逐场进行，
     * 同口严格一场接一场——执行者串行化模型的吞吐/时延基准形态。
     */
    @Test(timeout = 120_000)
    public void singlePortSerialDialoguesThroughputAndP99() throws Exception {
        Assume.assumeTrue("SERIAL_BENCH=1 时才执行（日常构建跳过）", System.getenv("SERIAL_BENCH") != null);

        String portName = "ttyBENCH-SERIAL";
        BenchHarness harness = new BenchHarness(portName);
        SerialSource session = new SerialSource(harness.port, "bench-serial");
        harness.attachReplyListener(session);
        try {
            BenchResult warmup = harness.runSerial(session, "serial-warmup", WARMUP_PER_SESSION);
            assertEquals("预热轮必须全完成", WARMUP_PER_SESSION, warmup.dialogues);

            BenchResult measured = harness.runSerial(session, "serial-measured", SERIAL_DIALOGUES);
            assertEquals("正式轮必须全完成（零异常零队满是前提）", SERIAL_DIALOGUES, measured.dialogues);
            System.out.println(measured.benchLine());
            measured.appendToFile();
        } finally {
            session.closePort();
        }
    }

    // ========== 组②：3 设备同口并发（排队形态观察） ==========

    /**
     * 3 设备视图同口并发对话：模拟同口多机排队——3 路并发提交（在飞 1 + 队列 ≤2，
     * 有界队列容量内不触发队满背压），观察排队下吞吐/时延不异常（不把绝对值写进断言）。
     */
    @Test(timeout = 120_000)
    public void threeDevicesConcurrentDialoguesQueueingStable() throws Exception {
        Assume.assumeTrue("SERIAL_BENCH=1 时才执行（日常构建跳过）", System.getenv("SERIAL_BENCH") != null);

        String portName = "ttyBENCH-CONC";
        BenchHarness harness = new BenchHarness(portName);
        SerialSource main = new SerialSource(harness.port, "bench-conc-0");
        harness.attachReplyListener(main);
        List<SerialSource> sessions = new ArrayList<>();
        sessions.add(main);
        for (int i = 1; i < CONCURRENT_DEVICES; i++) {
            sessions.add(new SerialSource(harness.port, "bench-conc-" + i));
        }
        try {
            // 预热：3 视图各一场（执行者/JIT 热身，数据弃置）
            for (SerialSource session : sessions) {
                harness.runSerial(session, "conc-warmup", 1);
            }

            final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> replies = harness.replies;
            final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            final CountDownLatch allDone = new CountDownLatch(CONCURRENT_DEVICES);
            final long[] perCallNanos = new long[CONCURRENT_DEVICES * DIALOGUES_PER_DEVICE];
            ExecutorService workers = Executors.newFixedThreadPool(CONCURRENT_DEVICES);
            long benchStart = System.nanoTime();
            for (int d = 0; d < CONCURRENT_DEVICES; d++) {
                final SerialSource session = sessions.get(d);
                final int deviceIdx = d;
                workers.execute(() -> {
                    try {
                        for (int i = 0; i < DIALOGUES_PER_DEVICE; i++) {
                            int seq = harness.nextSeq();
                            byte[] cmd = commandFrame(seq);
                            long t0 = System.nanoTime();
                            CompletableFuture<Boolean> reply = new CompletableFuture<>();
                            replies.put(seq, reply);
                            CompletableFuture<Boolean> tx = SerialTransactionStrategy.executeWithLambda(
                                    session, src -> {
                                        try {
                                            assertTrue("写命令必须被口接收",
                                                    src.asyncSendData(cmd).get(WRITE_ACK_WAIT_S, TimeUnit.SECONDS));
                                        } catch (Exception e) {
                                            throw new AssertionError("写命令未被口接收 seq=" + seq, e);
                                        }
                                        return reply;
                                    });
                            assertTrue("并发对话 seq=" + seq + " 必须完成",
                                    tx.get(DIALOGUE_WAIT_S, TimeUnit.SECONDS));
                            replies.remove(seq);
                            perCallNanos[deviceIdx * DIALOGUES_PER_DEVICE + i] = System.nanoTime() - t0;
                        }
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                    } finally {
                        allDone.countDown();
                    }
                });
            }
            assertTrue("3 设备并发组必须在时限内全部完成（零异常零队满）",
                    allDone.await(90, TimeUnit.SECONDS));
            long totalNanos = System.nanoTime() - benchStart;
            workers.shutdown();
            assertTrue("并发组不得有异常: " + firstFailure.get(), firstFailure.get() == null);
            assertTrue("应答映射必须清空（每场对话恰一应答）", replies.isEmpty());

            BenchResult result = new BenchResult("conc3x100", perCallNanos.length, totalNanos, perCallNanos);
            System.out.println(result.benchLine());
            result.appendToFile();
        } finally {
            for (SerialSource session : sessions) {
                session.closePort();
            }
        }
    }

    // ========== 对话装置：虚拟口 + 回声设备 + seq 应答匹配 ==========

    /**
     * 基准装置：mock 物理口（写成功语义）+ 回声设备（writeBytes 回调内注入应答帧，
     * 事件化到口执行线程）+ 监听器（按 seq 匹配完成应答 future）。
     * 端口名每用例独立，用例间互不挂靠。
     */
    static final class BenchHarness {
        final SerialSourcePort port;
        /** seq → 应答 future（监听器回调在口执行线程，ConcurrentHashMap 必要） */
        final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> replies = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger seqGen =
                new java.util.concurrent.atomic.AtomicInteger(0);

        BenchHarness(String portName) {
            port = new SerialSourcePort(new SerialInfo(portName, 9600, 8, 1, 0), 3, null);
            SerialPort mockPort = mock(SerialPort.class);
            when(mockPort.isOpen()).thenReturn(true);
            when(mockPort.bytesAvailable()).thenReturn(0);
            // 回声型模拟设备本体：收到命令帧（writeBytes=口执行线程物理写）→ 设备处理
            // 延迟（SerialSdkTimers 既有确定性调度缝到拍）后注入应答帧。handleIncomingData
            // 只投递事件不等待处理，回调线程无重入风险——OnBytes 与 BodyDone 各自排队串行。
            when(mockPort.writeBytes(any(byte[].class), anyLong())).thenAnswer(inv -> {
                byte[] tx = inv.getArgument(0);
                Integer seq = parseSeq(tx);
                if (seq != null) {
                    byte[] resp = replyFrame(seq);
                    if (DEVICE_DELAY_MS > 0) {
                        SerialSdkTimers.fireAfter(() -> port.handleIncomingData(resp, resp.length),
                                DEVICE_DELAY_MS);
                    } else {
                        port.handleIncomingData(resp, resp.length);
                    }
                }
                return tx.length;
            });
            port.serialPort = mockPort;
        }

        int nextSeq() {
            return seqGen.incrementAndGet();
        }

        /** 在主视图挂 seq 应答监听器（回声通知遍历同口全部视图，挂主视图一份即够） */
        void attachReplyListener(SerialSource mainSession) {
            mainSession.onFrame(frame -> {
                Integer seq = parseSeq(frame);
                if (seq != null) {
                    CompletableFuture<Boolean> reply = replies.get(seq);
                    if (reply != null) {
                        reply.complete(Boolean.TRUE);
                    }
                }
            });
        }

        /** 串行基准：同会话连续 n 场对话（提交→等待→下一场），返回计测结果 */
        BenchResult runSerial(SerialSource session, String group, int n) throws Exception {
            long[] latencies = new long[n];
            long benchStart = System.nanoTime();
            for (int i = 0; i < n; i++) {
                int seq = nextSeq();
                byte[] cmd = commandFrame(seq);
                long t0 = System.nanoTime();
                CompletableFuture<Boolean> reply = new CompletableFuture<>();
                replies.put(seq, reply);
                CompletableFuture<Boolean> tx = SerialTransactionStrategy.executeWithLambda(
                        session, src -> {
                            try {
                                assertTrue("写命令必须被口接收",
                                        src.asyncSendData(cmd).get(WRITE_ACK_WAIT_S, TimeUnit.SECONDS));
                            } catch (Exception e) {
                                throw new AssertionError("写命令未被口接收 seq=" + seq, e);
                            }
                            return reply;
                        });
                assertTrue("对话 seq=" + seq + " 必须完成", tx.get(DIALOGUE_WAIT_S, TimeUnit.SECONDS));
                replies.remove(seq);
                latencies[i] = System.nanoTime() - t0;
            }
            long totalNanos = System.nanoTime() - benchStart;
            assertTrue("应答映射必须清空（每场对话恰一应答）", replies.isEmpty());
            return new BenchResult(group, n, totalNanos, latencies);
        }
    }

    /** 命令帧：STX + seq 大端两字节 + ETX */
    private static byte[] commandFrame(int seq) {
        return new byte[]{STX, (byte) ((seq >> 8) & 0xFF), (byte) (seq & 0xFF), ETX};
    }

    /** 应答帧：STX + seq 大端两字节 + ACK + ETX（回声设备语义） */
    private static byte[] replyFrame(int seq) {
        return new byte[]{STX, (byte) ((seq >> 8) & 0xFF), (byte) (seq & 0xFF), ACK, ETX};
    }

    /** 从帧内解析 seq；非本协议帧返回 null（严格模式：未知帧不猜） */
    private static Integer parseSeq(byte[] frame) {
        if (frame == null || frame.length < 4 || frame[0] != STX || frame[frame.length - 1] != ETX) {
            return null;
        }
        return ((frame[1] & 0xFF) << 8) | (frame[2] & 0xFF);
    }
}
