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

package com.ecat.integration.SerialIntegration.ConfigFlows;

import com.ecat.core.ConfigFlow.AbstractSubConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

import java.util.HashMap;
import java.util.Map;

/**
 * 串口通讯子 flow（单步形态）：通讯配置 → 落盘 comm_settings（扁平 7 字段，
 * 与串口域 11 仓现行主流一致，存量 entry 零迁移）。
 *
 * <p>供设备集成宿主 flow 挂载，两种形态：
 * <ul>
 *   <li>{@code registerFlowStep(new SerialCommFlow(), "宿主尾步")} —— 通讯参数全取库标准默认；</li>
 *   <li>{@code registerFlowStep(SerialCommFlow.builder().schema(...).build(), "宿主尾步")}
 *       —— 宿主定制 schema 默认值（波特率/超时等预填），定制面=SerialCommConfigSchema
 *       既有的 builder，子 flow 不新造参数名。</li>
 * </ul>
 * 通道承载演进（serial/TCP 分流）在本库内加步承接，采纳宿主零修改——入口由本类
 * registerStepEntry 显式声明，宿主不点名内部 stepId。
 * 设备侧读出经 {@link com.ecat.integration.SerialIntegration.SerialCommSettings#parse}（唯一合法通道）。
 *
 * @author coffee
 */
public class SerialCommFlow extends AbstractSubConfigFlow {

    private final SerialCommConfigSchema serialSchema;

    /** 无参构造 = 库标准默认（最简挂载形态）。 */
    public SerialCommFlow() {
        this.serialSchema = new SerialCommConfigSchema();
        registerSteps();
    }

    private SerialCommFlow(Builder b) {
        this.serialSchema = b.serialSchema;
        registerSteps();
    }

    private void registerSteps() {
        // 单步形态：入口即唯一步（serial 域当前只有一种通道，无协议选择步）
        registerStepEntry("comm_config", this::stepCommConfig, "通讯配置");
    }

    private ConfigFlowResult stepCommConfig(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("comm_config", serialSchema.createSchema(), new HashMap<>());
        }
        ConfigSchema schema = serialSchema.createSchema();
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("comm_config", schema, errors);
        }
        context.getEntryData().put("comm_settings", userInput);
        return subFlowComplete();   // 出口：交回宿主尾步（挂载时由宿主指定）
    }

    // ========== Builder：宿主定制 schema 默认值的唯一通道 ==========

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 不设置 = 库标准 schema；宿主传入自己用 schema builder 构建的实例覆盖表单预填。
     * 定制只影响写端预填——读端 parse 的缺字段兜底恒为库标准。
     */
    public static class Builder {
        private SerialCommConfigSchema serialSchema = new SerialCommConfigSchema();

        /** 定制预填（波特率/超时等）：传 SerialCommConfigSchema.builder() 构建的实例。 */
        public Builder schema(SerialCommConfigSchema serialSchema) {
            this.serialSchema = serialSchema;
            return this;
        }

        public SerialCommFlow build() {
            return new SerialCommFlow(this);
        }
    }
}
