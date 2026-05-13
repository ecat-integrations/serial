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
 * 波特率枚举
 *
 * @author coffee
 */
public enum BaudRate {
    BAUD_1200("1200", "1200"),
    BAUD_2400("2400", "2400"),
    BAUD_4800("4800", "4800"),
    BAUD_9600("9600", "9600"),
    BAUD_19200("19200", "19200"),
    BAUD_38400("38400", "38400"),
    BAUD_57600("57600", "57600"),
    BAUD_115200("115200", "115200");

    private final String value;
    private final String label;

    BaudRate(String value, String label) {
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
        for (BaudRate br : values()) {
            map.put(br.value, br.label);
        }
        return map;
    }
}
