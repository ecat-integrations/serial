package com.ecat.integration.SerialIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.DefaultResponseHandlerStrategy;
import com.fazecast.jSerialComm.SerialPort;

/**
 * 构造期环境判定只认显式信号（2026-09-18 saimosen v2 运行期重加全离线事故回归锁）。
 *
 * <p>事故形态：ruoyi Spring MVC 以 {@code Method.invoke} 反射分发 REST 请求，线程栈恒含
 * sun.reflect 帧——构造期栈扫描把这些生产线程误判为测试环境，凡经 ASM/ADM/env 页面或 REST
 * 运行期创建的串口设备，其响应策略被静默降级 legacy 轮询模式（重启后由启动 restore 线程
 * 重建才恢复，与部署侧「重启才好」吻合）。本测试锁三条契约：
 * <ol>
 *   <li>判定只认显式信号：test.mode 系统属性 / 源对象 isTestMode / mock-代理类名；</li>
 *   <li>构造线程的栈形态（含反射分发的 sun.reflect 帧）不得参与判定；</li>
 *   <li>类名含 $ 的动态生成源在无属性信号时仍判 legacy；mockito mock（内联插桩不改
 *       类名，类名检测天然不命中）经测试 JVM 的 test.mode 属性信号保 legacy——两者合
 *       起来是既有 mock/代理派测试的兼容基线。</li>
 * </ol>
 */
public class EnvDetectionExplicitSignalOnlyTest {

    /** 可以抛受检异常的断言体（策略构造/字段读取带 ReflectiveOperationException）。 */
    private interface ThrowingAssertion {
        void run() throws Exception;
    }

    /**
     * 在指定系统属性临时取值下执行断言体，结束恢复原值。surefire 同 fork 跑全部测试类，
     * 恢复原值（而非清空）保证后续测试看到的属性与本测试前一致。
     */
    private void withSystemProperty(String key, String value, ThrowingAssertion assertion) throws Exception {
        String original = System.getProperty(key);
        System.setProperty(key, value);
        try {
            assertion.run();
        } finally {
            if (original != null) {
                System.setProperty(key, original);
            } else {
                System.clearProperty(key);
            }
        }
    }

    /** 在显式 test.mode 取值下执行断言体（委托 {@link #withSystemProperty}）。 */
    private void withTestMode(String value, ThrowingAssertion assertion) throws Exception {
        withSystemProperty("test.mode", value, assertion);
    }

    /** 模式位读取：两策略类的判定结果都落在构造期定型的私有 useLegacyMode 字段。 */
    private static boolean legacyModeOf(Object strategy) throws Exception {
        Field mode = strategy.getClass().getDeclaredField("useLegacyMode");
        mode.setAccessible(true);
        return mode.getBoolean(strategy);
    }

    /**
     * 真实（非 mock）SerialSource：直构 SerialSourcePort 后预置 mock 物理口（isOpen=true），
     * 使 SerialSource 构造内的 registerSource→openPort 走 already-opened 早退分支——
     * 环境判定路径全真实，硬件路径零调用。
     */
    private SerialSource newRealSourceOnPresetPort() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("ttyUT-ENVPROBE", 9600, 8, 1, SerialPort.NO_PARITY), 1, null);
        SerialPort physical = mock(SerialPort.class);
        when(physical.isOpen()).thenReturn(true);
        port.serialPort = physical;
        return new SerialSource(port, "env-probe");
    }

    /** 反射构造工厂：Method.invoke 使栈携带 sun.reflect 帧（复刻 Spring MVC 反射分发形态）。 */
    private static ByteResponseHandlerStrategy<String> newByteStrategyViaReflectiveDispatch(SerialSource source) {
        return new ByteResponseHandlerStrategy<>(source, ctx -> true, bytes -> null, ex -> false, 200L);
    }

    /** 反射构造工厂（String 族孪生策略，同 {@link #newByteStrategyViaReflectiveDispatch}）。 */
    private static DefaultResponseHandlerStrategy<String> newDefaultStrategyViaReflectiveDispatch(SerialSource source) {
        return new DefaultResponseHandlerStrategy<>(source, ctx -> true, text -> null, ex -> false, 200L);
    }

    @Test
    public void port_constructedWithProductionSignal_mustNotBeTestMode() throws Exception {
        withTestMode("false", () -> {
            SerialSourcePort port = new SerialSourcePort(
                    new SerialInfo("ttyUT-ENVPROBE-P", 9600, 8, 1, SerialPort.NO_PARITY), 1, null);
            assertFalse("test.mode=false 下构造的端口不得进测试模式——构造线程栈形态不得参与判定"
                    + "（栈扫描误判是运行期重加全离线事故第一层根因）", port.isTestMode());
        });
    }

    @Test
    public void port_constructedWithTestModeProperty_mustBeTestMode() throws Exception {
        withTestMode("true", () -> {
            SerialSourcePort port = new SerialSourcePort(
                    new SerialInfo("ttyUT-ENVPROBE-T", 9600, 8, 1, SerialPort.NO_PARITY), 1, null);
            assertTrue("test.mode=true 是 surefire 为测试 JVM 注入的显式信号，端口必须进测试模式"
                    + "（既有 21 个直构端口的测试依赖此契约保持行为不变）", port.isTestMode());
        });
    }

    @Test
    public void port_constructedWithJunitProperty_mustBeTestMode() throws Exception {
        withSystemProperty("junit", "true", () -> withTestMode("false", () -> {
            SerialSourcePort port = new SerialSourcePort(
                    new SerialInfo("ttyUT-ENVPROBE-J", 9600, 8, 1, SerialPort.NO_PARITY), 1, null);
            assertTrue("junit 系统属性是属性级显式信号，端口应与两族策略一致认进测试模式"
                    + "（属性读取收敛到 SerialTestSignals 时对齐家族契约，此前端口漏认此信号）",
                    port.isTestMode());
        }));
    }

    @Test
    public void byteStrategy_constructedOnRealSourceWithProductionSignal_mustUseInterruptMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newRealSourceOnPresetPort();
            try {
                ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                        source, ctx -> true, bytes -> null, ex -> false, 200L);
                assertFalse("test.mode=false + 真实源：策略必须是生产（中断）模式——"
                        + "junit/sun.reflect 栈帧不得触发测试模式判定", legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void byteStrategy_constructedViaReflectionDispatch_mustUseInterruptMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newRealSourceOnPresetPort();
            try {
                Method factory = EnvDetectionExplicitSignalOnlyTest.class.getDeclaredMethod(
                        "newByteStrategyViaReflectiveDispatch", SerialSource.class);
                factory.setAccessible(true);
                ByteResponseHandlerStrategy<String> strategy =
                        (ByteResponseHandlerStrategy<String>) factory.invoke(null, source);
                assertFalse("反射分发栈（sun.reflect 帧）不得触发测试模式判定——ASM/ruoyi 运行期"
                        + "创建的设备策略正是该栈形态被误降级 legacy", legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    /**
     * 类名含 $ 的动态生成形态源：匿名子类类名形如 {@code EnvDetectionExplicitSignalOnlyTest$N}，
     * 复刻代理/字节码生成对象的类名信号。mockito-inline 不替换类名（内联插桩原类，
     * Mockito mock 的 getClass() 即原类），类名检测对 Mockito mock 天然不命中，
     * 故用匿名子类直接锁定该信号本身。
     */
    private SerialSource newDollarShapedSource() {
        SerialSourcePort port = new SerialSourcePort(
                new SerialInfo("ttyUT-ENVPROBE-D", 9600, 8, 1, SerialPort.NO_PARITY), 1, null);
        SerialPort physical = mock(SerialPort.class);
        when(physical.isOpen()).thenReturn(true);
        port.serialPort = physical;
        return new SerialSource(port, "dollar-shaped") {
        };
    }

    @Test
    public void byteStrategy_dollarShapedSourceClass_withoutPropertySignal_mustStayLegacyMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newDollarShapedSource();
            try {
                ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                        source, ctx -> true, bytes -> null, ex -> false, 200L);
                assertTrue("类名含 $ 的动态生成源是保留的显式信号，无属性信号时必须保持 legacy"
                        + "（代理/字节码生成对象的兼容基线）", legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    @Test
    public void byteStrategy_mockitoMockSource_underTestJvmProperty_mustStayLegacyMode() throws Exception {
        withTestMode("true", () -> {
            SerialSource mockSource = mock(SerialSource.class);
            ByteResponseHandlerStrategy<String> strategy = new ByteResponseHandlerStrategy<>(
                    mockSource, ctx -> true, bytes -> null, ex -> false, 200L);
            assertTrue("mockito mock 源在测试 JVM 属性信号下必须保持 legacy 轮询模式"
                    + "（存量 mock 派设备测试的轮询读闸依赖此路径）", legacyModeOf(strategy));
        });
    }

    @Test
    @SuppressWarnings({"deprecation", "unchecked"})
    public void defaultStrategy_constructedOnRealSourceWithProductionSignal_mustUseInterruptMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newRealSourceOnPresetPort();
            try {
                // String 族孪生策略虽标记 @Deprecated 仍在生产路径使用，检测逻辑与字节族复制同源，
                // 须同样锁死（事故调查确认两处栈扫描并存）
                DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                        source, ctx -> true, text -> null, ex -> false, 200L);
                assertFalse("test.mode=false + 真实源：String 族策略必须是生产（中断）模式",
                        legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    @Test
    @SuppressWarnings({"deprecation", "unchecked"})
    public void defaultStrategy_constructedViaReflectionDispatch_mustUseInterruptMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newRealSourceOnPresetPort();
            try {
                Method factory = EnvDetectionExplicitSignalOnlyTest.class.getDeclaredMethod(
                        "newDefaultStrategyViaReflectiveDispatch", SerialSource.class);
                factory.setAccessible(true);
                DefaultResponseHandlerStrategy<String> strategy =
                        (DefaultResponseHandlerStrategy<String>) factory.invoke(null, source);
                assertFalse("反射分发栈（sun.reflect 帧）不得触发 String 族策略的测试模式判定",
                        legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    @Test
    @SuppressWarnings("deprecation")
    public void defaultStrategy_dollarShapedSourceClass_withoutPropertySignal_mustStayLegacyMode() throws Exception {
        withTestMode("false", () -> {
            SerialSource source = newDollarShapedSource();
            try {
                DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                        source, ctx -> true, text -> null, ex -> false, 200L);
                assertTrue("类名含 $ 的动态生成源：String 族策略无属性信号时必须保持 legacy"
                        + "（代理/字节码生成对象的兼容基线）", legacyModeOf(strategy));
            } finally {
                source.closePort();
            }
        });
    }

    @Test
    @SuppressWarnings("deprecation")
    public void defaultStrategy_mockitoMockSource_underTestJvmProperty_mustStayLegacyMode() throws Exception {
        withTestMode("true", () -> {
            SerialSource mockSource = mock(SerialSource.class);
            DefaultResponseHandlerStrategy<String> strategy = new DefaultResponseHandlerStrategy<>(
                    mockSource, ctx -> true, text -> null, ex -> false, 200L);
            assertTrue("mockito mock 源在测试 JVM 属性信号下 String 族策略必须保持 legacy 模式"
                    + "（存量 mock 派设备测试的轮询读闸依赖此路径）", legacyModeOf(strategy));
        });
    }
}
