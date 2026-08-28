/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.SerialIntegration.ConfigSchemas;

import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;
import com.ecat.core.ConfigFlow.ConfigItem.DynamicEnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.NumericConfigItem;
import com.ecat.integration.SerialIntegration.Const;
import com.fazecast.jSerialComm.SerialPort;

import java.io.File;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 串口通讯 Schema - 可被其他集成复用
 * <p>
 * 定义了串口通讯的通用配置字段：
 * <ul>
 *   <li>serial_port - 串口设备路径</li>
 *   <li>baudrate - 波特率</li>
 *   <li>data_bits - 数据位</li>
 *   <li>stop_bits - 停止位</li>
 *   <li>parity - 校验位</li>
 *   <li>flow_control - 流控</li>
 *   <li>timeout - 超时时间</li>
 * </ul>
 * <p>
 * 使用方式：
 * <ul>
 *   <li>无参构造 → 标准默认值（向后兼容）</li>
 *   <li>Builder 构造 → 自定义默认值</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialCommConfigSchema implements ConfigSchemaProvider {

    // ========== 实例级默认值 ==========

    private final BaudRate defaultBaudRate;
    private final DataBits defaultDataBits;
    private final StopBits defaultStopBits;
    private final Parity defaultParity;
    private final FlowControl defaultFlowControl;
    private final int defaultTimeout;

    // ========== 测试用端口注入 ==========

    /**
     * 测试用端口注入点。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     * 设置后 {@link #getAvailablePorts()} 将直接返回此 Supplier 的结果，跳过 jSerialComm 硬件调用。
     * <p>
     * 用法示例（在测试的 @Before 中设置，@After 中清除）：
     * <pre>
     *   SerialCommConfigSchema.setTestPortSupplier(
     *       () -&gt; SerialCommConfigSchema.createTestPorts("ttyUSB0"));
     * </pre>
     */
    private static Supplier<Map<String, String>> testPortSupplier = null;

    /**
     * 注入测试用虚拟串口列表。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     *
     * @param supplier 返回虚拟端口 Map 的 Supplier，传 null 等同于 {@link #clearTestPortSupplier()}
     */
    public static void setTestPortSupplier(Supplier<Map<String, String>> supplier) {
        testPortSupplier = supplier;
    }

    /**
     * 清除测试用端口注入。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     */
    public static void clearTestPortSupplier() {
        testPortSupplier = null;
    }

    /**
     * 创建一个标准的测试用虚拟串口列表。
     * <p>
     * <b>仅供单元测试使用，生产环境禁止调用！</b>
     *
     * @param portNames 虚拟端口名称，如 "ttyUSB0", "ttyUSB1"
     * @return 格式为 {"" -&gt; "-- 请选择串口 --", "ttyUSB0" -&gt; "ttyUSB0", ...} 的 Map
     */
    public static Map<String, String> createTestPorts(String... portNames) {
        Map<String, String> ports = new LinkedHashMap<>();
        ports.put("", "-- 请选择串口 --");
        for (String name : portNames) {
            ports.put(name, name);
        }
        return ports;
    }

    // ========== 构造函数 ==========

    /**
     * 无参构造 → 标准默认值（向后兼容）
     */
    public SerialCommConfigSchema() {
        this.defaultBaudRate = BaudRate.BAUD_9600;
        this.defaultDataBits = DataBits.EIGHT;
        this.defaultStopBits = StopBits.ONE;
        this.defaultParity = Parity.NONE;
        this.defaultFlowControl = FlowControl.NONE;
        this.defaultTimeout = Const.READ_TIMEOUT_MS;
    }

    /**
     * Builder 构造 → 自定义默认值
     */
    private SerialCommConfigSchema(Builder builder) {
        this.defaultBaudRate = builder.baudRate;
        this.defaultDataBits = builder.dataBits;
        this.defaultStopBits = builder.stopBits;
        this.defaultParity = builder.parity;
        this.defaultFlowControl = builder.flowControl;
        this.defaultTimeout = builder.timeout;
    }

    // ========== ConfigSchemaProvider ==========

    @Override
    public String getI18nKeyPrefix() {
        return "config_schemas.serial_comm";
    }

    @Override
    public ConfigSchema createSchema() {
        ConfigSchema schema = new ConfigSchema()
            // 串口选择
            .addField(new DynamicEnumConfigItem("serial_port", true, "/dev/ttyUSB0",
                    SerialCommConfigSchema::getAvailablePorts)
                .displayName("串口"))
            // 波特率
            .addField(new EnumConfigItem("baudrate", true, defaultBaudRate.getValue())
                .displayName("波特率")
                .addOptions(BaudRate.toMap())
                .buildValidator())
            // 数据位
            .addField(new EnumConfigItem("data_bits", true, defaultDataBits.getValue())
                .displayName("数据位")
                .addOptions(DataBits.toMap())
                .buildValidator())
            // 停止位
            .addField(new EnumConfigItem("stop_bits", true, defaultStopBits.getValue())
                .displayName("停止位")
                .addOptions(StopBits.toMap())
                .buildValidator())
            // 校验位
            .addField(new EnumConfigItem("parity", true, defaultParity.getValue())
                .displayName("校验位")
                .addOptions(Parity.toMap())
                .buildValidator())
            // 流控 (value = SerialPort.FLOW_CONTROL_* 常量组合)
            .addField(new EnumConfigItem("flow_control", false, defaultFlowControl.getValue())
                .displayName("流控")
                .addOptions(FlowControl.toMap())
                .buildValidator())
            // 超时时间
            .addField(new NumericConfigItem("timeout", false, defaultTimeout)
                .displayName("超时时间(ms)")
                .range(100.0, 60000.0));

        schema.initI18n(this);
        return schema;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BaudRate baudRate = BaudRate.BAUD_9600;
        private DataBits dataBits = DataBits.EIGHT;
        private StopBits stopBits = StopBits.ONE;
        private Parity parity = Parity.NONE;
        private FlowControl flowControl = FlowControl.NONE;
        private int timeout = Const.READ_TIMEOUT_MS;

        public Builder baudrate(BaudRate baudRate) { this.baudRate = baudRate; return this; }
        public Builder dataBits(DataBits dataBits) { this.dataBits = dataBits; return this; }
        public Builder stopBits(StopBits stopBits) { this.stopBits = stopBits; return this; }
        public Builder parity(Parity parity) { this.parity = parity; return this; }
        public Builder flowControl(FlowControl flowControl) { this.flowControl = flowControl; return this; }
        public Builder timeout(int timeout) { this.timeout = timeout; return this; }

        public SerialCommConfigSchema build() {
            return new SerialCommConfigSchema(this);
        }
    }

    // ========== 辅助方法 ==========

    // ========== 枚举源 seam（可注入，默认真实实现） ==========

    /**
     * jSerialComm API 枚举提供器。
     * <p>
     * 契约：返回裸端口映射（portName -&gt; displayName），不含 "" 提示头；非 null，无口时为空 Map。
     * <b>仅供单元测试注入，生产环境禁止修改！</b>
     */
    static Supplier<Map<String, String>> apiPortOptionsSupplier = SerialCommConfigSchema::enumerateViaJSerialComm;

    /**
     * Linux /dev tty 设备节点扫描提供器。
     * <p>
     * 契约：返回裸端口映射（portName -&gt; displayName），不含 "" 提示头；非 null，无口时为空 Map。
     * <b>仅供单元测试注入，生产环境禁止修改！</b>
     */
    static Supplier<Map<String, String>> devNodeScanSupplier = SerialCommConfigSchema::scanDevTtyNodes;

    /**
     * Windows 判定（true = 只走 jSerialComm API，不扫 /dev）。
     * <p>
     * <b>仅供单元测试注入，生产环境禁止修改！</b>
     */
    static BooleanSupplier windowsDetector = SerialCommConfigSchema::detectWindows;

    /**
     * 恢复三个枚举 seam 为真实默认实现（测试 @After 调用，防止注入泄漏到其他测试），
     * 并清空枚举缓存（防上个测试的缓存 union 泄漏到下个测试的 seam 数据断言）。
     */
    static void resetEnumerationSeams() {
        apiPortOptionsSupplier = SerialCommConfigSchema::enumerateViaJSerialComm;
        devNodeScanSupplier = SerialCommConfigSchema::scanDevTtyNodes;
        windowsDetector = SerialCommConfigSchema::detectWindows;
        invalidateEnumerationCache();
    }

    // ========== 枚举缓存 + 首载串行化（bug-record-20260826-003500） ==========

    /**
     * 枚举互斥锁：串行化「jSerialComm 首次类加载 + 端口枚举」。jSerialComm 在共享父加载器
     * 上是 parallel-capable 包，多线程并发首触 getCommPorts 会竞态 definePackage
     * （AssertionError: Package ... has already been defined）——entry-restore 曾 4 线程并行
     * 首触致 18 个串口集成全灭。锁内单线程完成首次加载后，后续调用只剩纯系统调用，无竞态窗口。
     */
    private static final Object ENUMERATION_LOCK = new Object();

    /** 缓存的 union 枚举结果（不可变副本；null = 无缓存）。getCommPorts/扫 /dev 都是系统调用，TTL 内复用。 */
    private static volatile Map<String, String> cachedUnion;

    /** 缓存写入时刻（System.currentTimeMillis()）。 */
    private static volatile long cachedUnionAtMillis;

    /**
     * 枚举缓存 TTL（毫秒）。应用场景：启动 entry-restore 突发（18+ 坐标同窗校验）与
     * ConfigFlow 步骤渲染的重复 getOptions；热插拔新口最迟一个 TTL 后可见。
     * package-private volatile 仅为单测确定性调 TTL（生产勿改，与 seam 同界）。
     */
    static volatile long enumerationCacheTtlMs = 5000;

    private static void invalidateEnumerationCache() {
        synchronized (ENUMERATION_LOCK) {
            cachedUnion = null;
            cachedUnionAtMillis = 0L;
        }
    }

    /**
     * TTL 缓存包裹的真实枚举：首次（或缓存过期）在 {@link #ENUMERATION_LOCK} 内单线程执行
     * API 枚举 ∪ /dev 扫描并写缓存；命中则直接复用。
     */
    private static Map<String, String> enumerateUnionCached() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = cachedUnion;
        if (cached != null && now - cachedUnionAtMillis < enumerationCacheTtlMs) {
            return cached;
        }
        synchronized (ENUMERATION_LOCK) {
            // 双检：等锁期间前一线程可能已完成枚举（并发首触正是 003500 的形态）
            long recheck = System.currentTimeMillis();
            cached = cachedUnion;
            if (cached != null && recheck - cachedUnionAtMillis < enumerationCacheTtlMs) {
                return cached;
            }
            Map<String, String> union = new LinkedHashMap<>();
            // 源 1：jSerialComm API（Windows 上 COM 口的唯一来源；Linux 上提供 USB/PCI 总线口及描述）
            union.putAll(apiPortOptionsSupplier.get());
            // 源 2：/dev 确定性节点扫描（两源的取舍见 getAvailablePorts Javadoc）
            if (!windowsDetector.getAsBoolean()) {
                for (Map.Entry<String, String> entry : devNodeScanSupplier.get().entrySet()) {
                    union.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            cachedUnion = new LinkedHashMap<>(union);
            cachedUnionAtMillis = System.currentTimeMillis();
            return cachedUnion;
        }
    }

    /**
     * 启动期预热（bug-record-20260826-003500 修复方向 a+c）：在 serial 集成生命周期
     * （{@code SerialIntegration.onStart}，IntegrationManager 单线程加载阶段）触发首次真实
     * 枚举——jSerialComm 的类与包在唯一线程上完成加载/定义，且结果写入 TTL 缓存供随后的
     * entry-restore 并行阶段复用。这使「4 线程并行首触 jSerialComm」的竞态窗口在时序上
     * 不可能再出现（集成加载严格先于 entry-restore，见 onStart 调用点注释）。
     *
     * <p>预热走真实枚举路径（绕过 testPortSupplier——预热的意义就是触碰 jSerialComm 本体）。
     * 严格模式：环境级失败（native 库不可用等）原样上抛由集成加载失败面呈现，不吞不改。
     */
    public static void warmUp() {
        enumerateUnionCached();
    }

    /** /dev 下参与确定性扫描的串口设备节点前缀（USB 串口 / 传统 8250 / USB CDC-ACM） */
    private static final String[] DEV_TTY_PREFIXES = { "ttyUSB", "ttyS", "ttyACM" };

    /**
     * 串口名自然序：数字段按数值比较（ttyUSB2 &lt; ttyUSB10 &lt; ttyUSB100），字母段按字符比较。
     * <p>
     * 内核串口编号无前导零，数字段「先比长度再比字典」即等价数值序。
     */
    private static final Comparator<String> NATURAL_PORT_ORDER = (a, b) -> {
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) {
                    ja++;
                }
                int jb = ib;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) {
                    jb++;
                }
                String da = a.substring(ia, ja);
                String db = b.substring(ib, jb);
                if (da.length() != db.length()) {
                    return da.length() - db.length();
                }
                int digitCmp = da.compareTo(db);
                if (digitCmp != 0) {
                    return digitCmp;
                }
                ia = ja;
                ib = jb;
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                ia++;
                ib++;
            }
        }
        return (a.length() - ia) - (b.length() - ib);
    };

    /**
     * 获取系统可用的串口列表。
     * <p>
     * 选项集 = jSerialComm API 枚举 ∪ /dev tty 节点扫描（仅 Linux），重叠口去重（API 描述优先），
     * 自然序排序。
     * <p>
     * <b>为什么不能只用 jSerialComm API：</b>jSerialComm 2.9.x 的 Linux 枚举从 2.6.x 的
     * /dev 目录扫描改为 walk {@code /sys/class/tty/} 并按 {@code device/subsystem}
     * 符号链接认口（USB-serial 总线口 / 8250 传统口）。tty0tty 等内核虚拟串口对在 sysfs
     * 中无 {@code device} 符号链接，且其 /dev/ttyUSB* 命名只是 userspace symlink（内核
     * 设备名是 tnt*），sysfs walk 永远看不到——升级 2.9.3（为 Win2003 32 位 native 兼容，
     * 2.6.2 的 win-x86 dll 调用 Vista+ API 无法回退）后 Linux 选项集塌缩到 {ttyS0}，
     * 已有 entry 的 serial_port DynamicEnum 校验全挂。union 保证选项集覆盖真实存在的节点，
     * 校验语义不变：仍只接受真实存在的口，打开失败由 jSerialComm 在运行时报错。
     *
     * @return 串口选项映射 (portName -> displayName)；有口时首项为"请选择串口"提示，
     *         无口时仅返回"无串口信息"提示项
     */
    public static Map<String, String> getAvailablePorts() {
        // 测试注入点：如果设置了测试端口 Supplier，直接返回虚拟端口，不调用 jSerialComm
        if (testPortSupplier != null) {
            return testPortSupplier.get();
        }

        // union 枚举经 TTL 缓存 + 首载串行化出口（003500）：源构成与取舍见 enumerateUnionCached
        // 及本方法 Javadoc；此处只做排序与提示项装饰（纯内存操作，不缓存装饰结果）。
        Map<String, String> union = enumerateUnionCached();

        Map<String, String> sorted = new TreeMap<>(NATURAL_PORT_ORDER);
        sorted.putAll(union);

        Map<String, String> ports = new LinkedHashMap<>();
        if (sorted.isEmpty()) {
            // 无串口时提示 - 使用特殊占位值，空字符串不会提交到后端
            ports.put("", "-- 无串口信息 --");
        } else {
            // 首项：请选择串口（默认选项，不预设具体串口）
            ports.put("", "-- 请选择串口 --");
            ports.putAll(sorted);
        }
        return ports;
    }

    /**
     * jSerialComm API 枚举（真实默认实现）。
     *
     * @return 裸端口映射；getCommPorts 在个别平台返回 null（历史行为），按无口处理
     */
    private static Map<String, String> enumerateViaJSerialComm() {
        Map<String, String> result = new LinkedHashMap<>();
        SerialPort[] serialPorts = SerialPort.getCommPorts();
        if (serialPorts == null) {
            return result;
        }
        for (SerialPort port : serialPorts) {
            String portName = port.getSystemPortName();
            String description = port.getPortDescription();
            // 显示格式: "描述 (端口名)" 或直接端口名
            String displayName = (description != null && !description.trim().isEmpty())
                    ? description + " (" + portName + ")"
                    : portName;
            result.put(portName, displayName);
        }
        return result;
    }

    /**
     * Linux /dev tty 设备节点确定性扫描（真实默认实现）。
     * <p>
     * 按 {@link #DEV_TTY_PREFIXES} 前缀列 /dev 下实际存在的节点名（symlink 亦为存在——
     * tty0tty 环境的 /dev/ttyUSB* 即指向 /dev/tnt* 的 symlink）。显式设计边界：
     * 节点存在即可选，是否真能打开由 jSerialComm 打开时报告，此处不做能力探测。
     * listFiles 对不存在/不可读目录返回 null 是 File API 契约，按该前缀无结果处理。
     *
     * @return 裸端口映射（portName -&gt; portName，无硬件描述可用）
     */
    private static Map<String, String> scanDevTtyNodes() {
        Map<String, String> result = new TreeMap<>(NATURAL_PORT_ORDER);
        File devDir = new File("/dev");
        for (String prefix : DEV_TTY_PREFIXES) {
            File[] nodes = devDir.listFiles((dir, name) -> name.startsWith(prefix));
            if (nodes == null) {
                continue;
            }
            for (File node : nodes) {
                result.put(node.getName(), node.getName());
            }
        }
        return new LinkedHashMap<>(result);
    }

    /**
     * Windows 平台判定（真实默认实现）。
     */
    private static boolean detectWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase().contains("win");
    }
}
