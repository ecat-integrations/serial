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
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SerialCommConfigSchema 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>Schema 字段定义正确（7 个字段）</li>
 *   <li>Schema 验证功能正常（必填字段检查）</li>
 * </ul>
 *
 * @author coffee
 */
public class SerialCommConfigSchemaTest {

    @Test
    public void testSchemaDefinitionCorrect() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        assertNotNull("Schema 不应为 null", schema);
        List<AbstractConfigItem<?>> fields = schema.getFields();
        assertEquals("应该有 7 个字段", 7, fields.size());

        assertFieldExists(fields, "serial_port");
        assertFieldExists(fields, "baudrate");
        assertFieldExists(fields, "data_bits");
        assertFieldExists(fields, "stop_bits");
        assertFieldExists(fields, "parity");
        assertFieldExists(fields, "timeout");
    }

    @Test
    public void testSchemaValidation_ValidData() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        Map<String, String> availablePorts = SerialCommConfigSchema.getAvailablePorts();
        String validPort = availablePorts.keySet().stream()
            .filter(k -> !k.isEmpty())
            .findFirst()
            .orElse(null);

        if (validPort != null) {
            Map<String, Object> validData = new java.util.HashMap<>();
            validData.put("serial_port", validPort);
            validData.put("baudrate", "9600");
            validData.put("data_bits", "8");
            validData.put("stop_bits", "1");
            validData.put("parity", "None");

            Map<String, Object> errors = schema.validate(validData);
            assertTrue("有效数据应该通过验证", errors.isEmpty());
        }
    }

    @Test
    public void testSchemaValidation_MissingRequiredField() {
        SerialCommConfigSchema schemaProvider = new SerialCommConfigSchema();
        ConfigSchema schema = schemaProvider.createSchema();

        Map<String, Object> invalidData = new java.util.HashMap<>();

        Map<String, Object> errors = schema.validate(invalidData);
        assertFalse("缺少必填字段应该有错误", errors.isEmpty());
        assertTrue("应该有关于 serial_port 字段的错误", errors.containsKey("serial_port"));
    }

    private void assertFieldExists(List<AbstractConfigItem<?>> fields, String key) {
        assertNotNull("字段列表不应为 null", fields);
        for (AbstractConfigItem<?> field : fields) {
            if (field.getKey().equals(key)) {
                return;
            }
        }
        fail("字段 '" + key + "' 不存在");
    }
}
