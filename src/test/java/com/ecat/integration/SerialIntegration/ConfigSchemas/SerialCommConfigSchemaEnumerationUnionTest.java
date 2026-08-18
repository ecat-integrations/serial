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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * getAvailablePorts() union 枚举语义锁定测试（bug-record-20260816-215217）。
 *
 * <p>背景：jSerialComm 2.6.2 → 2.9.3 后 Linux 枚举只认 sysfs 里带
 * device/subsystem 的总线串口（USB-serial/8250），tty0tty 虚拟对
 * （/dev/tnt* 无 device 链接）与 /dev symlink 命名全部漏掉，
 * 导致 serial_port DynamicEnum 选项集 = {ttyS0}、41 entry 校验失败。
 *
 * <p>锁定的 union 语义：
 * <ul>
 *   <li>选项集 = jSerialComm API 枚举 ∪ /dev tty 节点扫描（Linux）</li>
 *   <li>重叠口去重，API 的描述性显示名优先</li>
 *   <li>Windows 不扫 /dev（COM 口枚举走 jSerialComm API）</li>
 *   <li>自然序排序（ttyUSB2 &lt; ttyUSB10 &lt; ttyUSB100）</li>
 *   <li>两源皆空 → 仅「无串口信息」占位项</li>
 * </ul>
 *
 * <p>全部枚举源经 seam 注入（apiPortOptionsSupplier / devNodeScanSupplier /
 * windowsDetector），不真依赖宿主机 /dev 内容。
 *
 * @author coffee
 */
public class SerialCommConfigSchemaEnumerationUnionTest {

    @Before
    public void setUp() {
        // 全旁路注入必须关闭，走被测的 union 路径
        SerialCommConfigSchema.clearTestPortSupplier();
    }

    @After
    public void tearDown() {
        SerialCommConfigSchema.clearTestPortSupplier();
        SerialCommConfigSchema.resetEnumerationSeams();
    }

    private static Map<String, String> ports(String... names) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, name);
        }
        return map;
    }

    // ========== ① union：API 只回 ttyS0，FS 扫到 ttyUSB100-102 → 全部 4 个都在 ==========

    @Test
    public void unionContainsBothApiAndScannedPorts() {
        Map<String, String> apiPorts = new LinkedHashMap<>();
        apiPorts.put("ttyS0", "Physical Port S0 (ttyS0)");
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> apiPorts;
        SerialCommConfigSchema.devNodeScanSupplier =
            () -> ports("ttyUSB100", "ttyUSB101", "ttyUSB102");
        SerialCommConfigSchema.windowsDetector = () -> false;

        Map<String, String> result = SerialCommConfigSchema.getAvailablePorts();

        // 首项 = 请选择占位头
        assertEquals("-- 请选择串口 --", result.get(""));
        // union 含全部 4 个口（ttyS0 + ttyUSB100/101/102）
        assertNotNull("ttyS0（仅 API 枚举到）应在选项集中", result.get("ttyS0"));
        assertNotNull("ttyUSB100（仅 FS 扫描到）应在选项集中", result.get("ttyUSB100"));
        assertNotNull("ttyUSB101（仅 FS 扫描到）应在选项集中", result.get("ttyUSB101"));
        assertNotNull("ttyUSB102（仅 FS 扫描到）应在选项集中", result.get("ttyUSB102"));
        // API 的描述性显示名保留
        assertEquals("Physical Port S0 (ttyS0)", result.get("ttyS0"));
        // FS 扫到的口无描述 → 裸名即显示名
        assertEquals("ttyUSB100", result.get("ttyUSB100"));
        // 总数 = 占位头 + 4 口
        assertEquals(5, result.size());
    }

    // ========== ② 两源重叠去重 ==========

    @Test
    public void overlappingPortsDeduplicated_apiDisplayNameWins() {
        Map<String, String> apiPorts = new LinkedHashMap<>();
        apiPorts.put("ttyUSB100", "USB-Based Serial Port (ttyUSB100)");
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> apiPorts;
        SerialCommConfigSchema.devNodeScanSupplier = () -> ports("ttyUSB100");
        SerialCommConfigSchema.windowsDetector = () -> false;

        Map<String, String> result = SerialCommConfigSchema.getAvailablePorts();

        int occurrences = 0;
        for (String key : result.keySet()) {
            if ("ttyUSB100".equals(key)) {
                occurrences++;
            }
        }
        assertEquals("重叠口只应出现一次", 1, occurrences);
        assertEquals("重叠口显示名应取 API 的描述性名称",
            "USB-Based Serial Port (ttyUSB100)", result.get("ttyUSB100"));
    }

    // ========== ③ Windows 路径不扫 FS ==========

    @Test
    public void windowsPathDoesNotScanFilesystem() {
        Map<String, String> apiPorts = new LinkedHashMap<>();
        apiPorts.put("COM3", "COM3");
        apiPorts.put("COM5", "COM5");
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> apiPorts;
        // FS 扫描器一旦被调用即失败——Windows 分支必须完全不触碰它
        SerialCommConfigSchema.devNodeScanSupplier = () -> {
            throw new AssertionError("Windows 路径不应扫描 /dev 文件系统");
        };
        SerialCommConfigSchema.windowsDetector = () -> true;

        Map<String, String> result = SerialCommConfigSchema.getAvailablePorts();

        assertEquals("-- 请选择串口 --", result.get(""));
        assertEquals(3, result.size());
        assertEquals("COM3", result.get("COM3"));
        assertEquals("COM5", result.get("COM5"));
    }

    // ========== ④ 自然序排序（数字段按数值比较） ==========

    @Test
    public void portsSortedInNaturalOrder() {
        SerialCommConfigSchema.apiPortOptionsSupplier =
            () -> ports("ttyUSB100", "ttyS0", "ttyUSB2");
        SerialCommConfigSchema.devNodeScanSupplier =
            () -> ports("ttyUSB10");
        SerialCommConfigSchema.windowsDetector = () -> false;

        List<String> keys = new ArrayList<>(SerialCommConfigSchema.getAvailablePorts().keySet());

        // 占位头在最前，其后 ttyS0 < ttyUSB2 < ttyUSB10 < ttyUSB100（数字按数值序，非字典序）
        assertEquals(5, keys.size());
        assertEquals("", keys.get(0));
        assertEquals("ttyS0", keys.get(1));
        assertEquals("ttyUSB2", keys.get(2));
        assertEquals("ttyUSB10", keys.get(3));
        assertEquals("ttyUSB100", keys.get(4));
    }

    // ========== ⑤ 两源皆空 → 无串口占位项 ==========

    @Test
    public void emptySourcesYieldPlaceholderOnly() {
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> ports();
        SerialCommConfigSchema.devNodeScanSupplier = () -> ports();
        SerialCommConfigSchema.windowsDetector = () -> false;

        Map<String, String> result = SerialCommConfigSchema.getAvailablePorts();

        assertEquals(1, result.size());
        assertEquals("-- 无串口信息 --", result.get(""));
    }

    // ========== 校验语义闭环：union 后被漏掉的口重新成为合法选项 ==========

    @Test
    public void entryValidationPassesForPortOnlyVisibleViaDevScan() {
        Map<String, String> apiPorts = new LinkedHashMap<>();
        apiPorts.put("ttyS0", "Physical Port S0 (ttyS0)");
        SerialCommConfigSchema.apiPortOptionsSupplier = () -> apiPorts;
        SerialCommConfigSchema.devNodeScanSupplier = () -> ports("ttyUSB119");
        SerialCommConfigSchema.windowsDetector = () -> false;

        com.ecat.core.ConfigFlow.ConfigSchema schema = new SerialCommConfigSchema().createSchema();
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("serial_port", "ttyUSB119");
        data.put("baudrate", "9600");
        data.put("data_bits", "8");
        data.put("stop_bits", "1");
        data.put("parity", "None");

        Map<String, Object> errors = schema.validate(data);

        assertTrue("ttyUSB119（真实存在的 /dev 节点）应通过 serial_port 校验: " + errors,
            errors.isEmpty());
    }
}
