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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流控枚举
 * <p>
 * value 对应 SerialPort.FLOW_CONTROL_* 常量组合值
 *
 * @author coffee
 */
public enum FlowControl {
    NONE("0", "无"),
    RTS_CTS("17", "RTS/CTS (硬件)"),
    DTR_DSR("4352", "DTR/DSR (硬件)"),
    XON_XOFF("1114112", "XOn/XOff (软件)");

    private final String value;
    private final String label;

    FlowControl(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String getValue() { return value; }
    public String getLabel() { return label; }

    /**
     * 供 EnumConfigItem.addOptions() 直接使用
     */
    public static Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (FlowControl fc : values()) {
            map.put(fc.value, fc.label);
        }
        return map;
    }
}
