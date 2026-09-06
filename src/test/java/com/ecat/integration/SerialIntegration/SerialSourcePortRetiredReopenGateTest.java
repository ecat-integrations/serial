package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.fazecast.jSerialComm.SerialPort;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【RED：8-2 退役门】bugs/bug-record-20260901-214000 / bug-record-20260901-103824 同根因：
 * 事务硬超时强拆路径 {@code recoverWedgedPort} close 后<b>无条件 openPort 重开</b>，与
 * {@code unregisterSource}（空源 → closePort + integration.removePort 除名）零同步——
 * recovery 晚到几毫秒即把全新 fd 挂在已除名、零 source 的端口对象上：无人再关它（孤儿 fd），
 * 同口新设备 OPEN FAILED 直到 core 重启（lsof 实证）。
 *
 * <p>语义边界（用户确认，勿扩）：物理串口随时可重开——新设备注册走集成端口地图里的
 * <b>新</b> {@code SerialSourcePort} 对象，正常打开；只拦「已退役对象」（最后一个 source
 * 已注销、已从地图除名）上的迟到重开。有源端口（未退役）的 wedge 自愈重开行为保持。
 *
 * <p>确定性红测：顺序复刻现场可达序列（unregisterSource 到空源之后调 recoverWedgedPort），
 * 禁竞态赌博；判据走日志断言（本仓 fake 口名零副作用惯例——mock 串口 isOpen 跟随 closePort
 * 翻转，真 openPort 尝试经 /dev/null 表现为 [OPEN FAILED] 行，可观测且无真实串口依赖）。
 */
public class SerialSourcePortRetiredReopenGateTest {

    /**
     * 测试端口名用 /dev/null：jSerialComm getCommPort 对不存在的路径抛
     * SerialPortInvalidPortException，对存在文件返回对象（openPort 失败仅返回 false，不抛），
     * 使 openPort 全流程在无真实串口的 CI 环境可走到（同 SerialIntegrationReconfigureSettingsTest）。
     */
    private static final String PORT = "/dev/null";

    /** 注入 mock 串口（isOpen 跟随 closePort 翻转），使 openPort/closePort 行为可经日志观测。 */
    private SerialSourcePort newPortWithMockSerial(SerialIntegration integration) {
        SerialSourcePort port = new SerialSourcePort(new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500), 1, integration);
        SerialPort serialPort = mock(SerialPort.class);
        final boolean[] open = {true};
        when(serialPort.isOpen()).thenAnswer(inv -> open[0]);
        doAnswer(inv -> {
            open[0] = false;
            return true;
        }).when(serialPort).closePort();
        port.serialPort = serialPort;
        if (integration != null) {
            integration.serialPortsPutForTest(PORT, port);
        }
        return port;
    }

    /**
     * 红（主判据）：最后一个 source 注销（口关 + 除名）之后，迟到的 recoverWedgedPort
     * 必须<b>拒绝重开</b>——[OPEN-REJECTED] 出现且无重开尝试（无 [OPEN FAILED] 尝试行）。
     * 修复前：无 REJECTED、openPort 照常进入（[OPEN FAILED] port= 行）→ 红。
     */
    @Test
    public void recoverWedgedPortAfterRetirement_isRejected() {
        LogCapture logs = LogCapture.attachTo(SerialSourcePort.class);
        try {
            SerialSourcePort port = newPortWithMockSerial(null);
            SerialSource src = new SerialSource(port, "dev-1");   // register → openPort（already-open 短路）
            port.unregisterSource(src);                            // 空源 → closePort（无 integration，跳过 removePort）

            port.recoverWedgedPort("test");                       // 迟到自愈——现场可达序列

            assertTrue("退役对象上的迟到重开必须被拒（[OPEN-REJECTED] 行），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN-REJECTED]"));
            assertFalse("拒绝即不得有重开尝试（[OPEN FAILED] 尝试行），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN FAILED] port="));
        } finally {
            logs.close();
        }
    }

    /** 行为锁 a：仍有 source 时不退役——recoverWedgedPort 照常重开（无 REJECTED）。 */
    @Test
    public void recoverWedgedPort_withSourceAttached_stillReopens() {
        LogCapture logs = LogCapture.attachTo(SerialSourcePort.class);
        try {
            SerialSourcePort port = newPortWithMockSerial(null);
            new SerialSource(port, "dev-1");                       // 有源端口

            port.recoverWedgedPort("test");

            assertFalse("有源端口的 wedge 自愈重开不受退役门影响（无 [OPEN-REJECTED]），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN-REJECTED]"));
            assertTrue("重开必须照常进行（openPort 进入全流程，fake 口表现为 [OPEN FAILED] 尝试/完成行），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN FAILED] port=") || logs.has("[OPENED] port="));
        } finally {
            logs.close();
        }
    }

    /** 行为锁 b：两个 source 注销一个不退役（remaining 不为空，无拆除、无拒绝）。 */
    @Test
    public void unregisterOneOfTwoSources_doesNotRetire() {
        LogCapture logs = LogCapture.attachTo(SerialSourcePort.class);
        try {
            SerialSourcePort port = newPortWithMockSerial(null);
            SerialSource first = new SerialSource(port, "dev-1");
            new SerialSource(port, "dev-2");

            port.unregisterSource(first);                          // remaining=[dev-2] → 不进空源分支

            assertFalse("未到空源不得拆除端口（无 last source removed 行），实际日志：\n" + logs.timeline(),
                    logs.has("last source removed"));
            port.recoverWedgedPort("test");
            assertFalse("仍有 source 的端口不退役（无 [OPEN-REJECTED]），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN-REJECTED]"));
        } finally {
            logs.close();
        }
    }

    /**
     * 行为锁 c：退役后新注册走新对象不受影响——integration.removePort 除名后，register
     * 命中新 SerialSourcePort，其 openPort 走正常流程（fake 口下表现为进入 openPort 流程
     * 而非被拒）。
     */
    @Test
    public void registerAfterRetirement_goesThroughNewPortObjectAndOpensNormally() {
        LogCapture logs = LogCapture.attachTo(SerialSourcePort.class);
        try {
            SerialIntegration integration = new SerialIntegration();
            SerialSourcePort oldPort = newPortWithMockSerial(integration);
            SerialSource src = new SerialSource(oldPort, "dev-1");
            oldPort.unregisterSource(src);                         // 空源 → retired + integration.removePort（真实除名）

            SerialSource fresh = integration.register(new SerialInfo(PORT, 9600, 8, 1, 0, 0, 500), "dev-new");

            assertNotNull("新注册必须返回新 source", fresh);
            assertNotSame("退役对象已从端口地图除名，新注册必须走新 SerialSourcePort 对象",
                    oldPort, integration.serialPortsGet(PORT));
            assertFalse("新对象 openPort 不受退役门影响（无 [OPEN-REJECTED]），实际日志：\n" + logs.timeline(),
                    logs.has("[OPEN-REJECTED]"));
            assertTrue("新对象 openPort 走正常流程（fake 口表现为 [OPEN FAILED] identity=dev-new 尝试行），实际日志：\n" + logs.timeline(),
                    logs.has("identity=dev-new"));
        } finally {
            logs.close();
        }
    }

    // ==================== 日志观测基建（ListAppender 惯用法，同 SerialPollingSdkTest） ====================

    /**
     * 附加到目标类 logger 的 ListAppender 捕获器：断言日志事件（同步调用路径，调用返回即可断言）。
     * 附带摘下 logback 全局 TurboFilter（ErrorRateLimitFilter 对 WARN/ERROR 3s 窗口限频，
     * 会吞本节断言的 [OPEN FAILED]/[OPEN-REJECTED] 行），用毕恢复。
     */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        private final ch.qos.logback.classic.Level originalLevel;
        private final List<ch.qos.logback.classic.turbo.TurboFilter> savedTurboFilters;

        private LogCapture(ch.qos.logback.classic.Logger logger) {
            this.logger = logger;
            this.originalLevel = logger.getLevel();
            logger.setLevel(ch.qos.logback.classic.Level.INFO);
            appender.start();
            logger.addAppender(appender);
            ch.qos.logback.classic.LoggerContext ctx =
                    (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            this.savedTurboFilters = new ArrayList<>(ctx.getTurboFilterList());
            ctx.getTurboFilterList().clear();
        }

        static LogCapture attachTo(Class<?> clazz) {
            return new LogCapture((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(clazz));
        }

        /** appender 监视器下拷贝快照（ListAppender doAppend 同锁追加，读写互斥）。 */
        private List<ch.qos.logback.classic.spi.ILoggingEvent> snapshot() {
            synchronized (appender) {
                return new ArrayList<>(appender.list);
            }
        }

        boolean has(String fragment) {
            return snapshot().stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
        }

        /** 断言失败时的诊断时间线（消息原样列出，一眼定位）。 */
        String timeline() {
            return snapshot().stream()
                    .map(e -> "  [" + e.getLevel() + "] " + e.getFormattedMessage())
                    .reduce("", (acc, line) -> acc + "\n" + line);
        }

        @Override
        public void close() {
            ch.qos.logback.classic.LoggerContext ctx =
                    (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            ctx.getTurboFilterList().addAll(savedTurboFilters);
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }
}
