package com.ecat.integration.SerialIntegration;

/**
 * 测试环境显式信号的<b>唯一系统属性读取点</b>（运行时开关集中读取，禁各判定点散读
 * {@code System.getProperty}）。
 *
 * <p>属性信号只有两个，均为测试 JVM 专属，生产 JVM 永不设置：
 * <ul>
 *   <li>{@code test.mode}——surefire 在 pom {@code systemPropertyVariables} 显式声明，
 *       本仓与消费仓同款（见本仓 README「测试编写规范（应答策略双读法与 test.mode
 *       声明）」：mockito-inline 改写字节码后 mock 类名不含 {@code $} 标记，mock 源
 *       测试只能靠此属性声明测试读法）；</li>
 *   <li>{@code junit}——junit runner 启动时自设，兜住不经 surefire 的直跑形态。</li>
 * </ul>
 *
 * <p>判定逻辑（叠加源对象测试模式标记 / mock-代理类名等前级信号）仍在各判定点
 * （{@link SerialSourcePort} 与两族应答策略），本类只收敛属性读取。
 */
public final class SerialTestSignals {

    private SerialTestSignals() {
    }

    /** surefire 注入的 {@code test.mode=true} 显式声明（端口与两族策略共同认定）。 */
    public static boolean testModeDeclared() {
        return Boolean.parseBoolean(System.getProperty("test.mode", "false"));
    }

    /** junit runner 自设的 {@code junit} 系统属性存在（端口与两族策略共同认定）。 */
    public static boolean junitPropertyPresent() {
        return !System.getProperty("junit", "").isEmpty();
    }
}
