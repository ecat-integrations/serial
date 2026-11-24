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
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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
 *   <li>{@code registerFlowStep(SerialCommFlow.builder().defaults(entryData -> ...).build(), "宿主尾步")}
 *       —— 动态预填：进 comm 步时按宿主 entryData 求值（与静态 schema 并设时动态优先）。</li>
 * </ul>
 * 通道承载演进（serial/TCP 分流）在本库内加步承接，采纳宿主零修改——入口由本类
 * registerStepEntry 显式声明，宿主不点名内部 stepId。
 * 设备侧读出经 {@link com.ecat.integration.SerialIntegration.SerialCommSettings#parse}（唯一合法通道）。
 *
 * @author coffee
 */
public class SerialCommFlow extends AbstractSubConfigFlow {

    private final SerialCommConfigSchema serialSchema;

    /**
     * 动态预填 hook（两级默认值的动态级，§3.3）：进 comm 步渲染表单前按宿主 entryData 求值，
     * 返回值仅决定字段预填/枚举选项——不改变校验规则（执法恒为 createSchema().validate 按档）。
     * 契约：hook 抛异常原样炸到 flow 层（不吞、不转 errors）；返回 null 视为 hook 违约抛
     * IllegalStateException（不兜底回静态 schema）；与静态 schema 同时设置时本 hook 优先。
     */
    private final Function<Map<String, Object>, SerialCommConfigSchema> defaults;

    /** 无参构造 = 库标准默认（最简挂载形态）。 */
    public SerialCommFlow() {
        this.serialSchema = new SerialCommConfigSchema();
        this.defaults = null;
        registerSteps();
    }

    private SerialCommFlow(Builder b) {
        this.serialSchema = b.serialSchema;
        this.defaults = b.defaults;
        registerSteps();
    }

    private void registerSteps() {
        // 单步形态：入口即唯一步（serial 域当前只有一种通道，无协议选择步）
        registerStepEntry("comm_config", this::stepCommConfig, "通讯配置");
    }

    private ConfigFlowResult stepCommConfig(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("comm_config", resolveCommSchema().createSchema(), new HashMap<>());
        }
        // 按提交的 transport 档案校验（COM 档执法与既有逐字一致；TCP 档豁免 UART 字段、
        // host/port 由复用的 tcp 仓字段原生 required 执法——豁免集单点在 SerialCommConfigSchema）
        Map<String, Object> errors = serialSchema.validateForTransport(userInput);
        if (!errors.isEmpty()) {
            return showForm("comm_config", resolveCommSchema().createSchema(), errors);
        }
        context.getEntryData().put("comm_settings", userInput);
        return subFlowComplete();   // 出口：交回宿主尾步（挂载时由宿主指定）
    }

    /**
     * 表单 schema 解析（两级默认值求值点）：defaults hook 优先（按挂载时注入的宿主
     * context.entryData 动态预填），未设 hook 落到静态 schema（Builder.schema 定制，
     * 两者皆未设即库标准默认——Builder 既有初始化已覆盖第三级）。
     */
    private SerialCommConfigSchema resolveCommSchema() {
        if (defaults != null) {
            SerialCommConfigSchema resolved = defaults.apply(context.getEntryData());
            if (resolved == null) {
                throw new IllegalStateException(
                        "SerialCommFlow defaults hook 返回 null（契约：返回 SerialCommConfigSchema.builder() 产物）");
            }
            return resolved;
        }
        return serialSchema;
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
        private Function<Map<String, Object>, SerialCommConfigSchema> defaults;

        /** 定制预填（波特率/超时等）：传 SerialCommConfigSchema.builder() 构建的实例。 */
        public Builder schema(SerialCommConfigSchema serialSchema) {
            this.serialSchema = serialSchema;
            return this;
        }

        /**
         * 动态预填（两级默认值的动态级）：进 comm 步渲染表单前按宿主 entryData 求值。
         * 契约：hook 只决定字段默认值/枚举选项，不改变校验规则；抛异常炸到 flow 层不兜底；
         * 用户已填值永远优先（core 以 step_inputs[stepId] 回显，前端盖过 schema 预填）。
         * 与 {@link #schema} 同时设置时本 hook 优先（动态预填覆盖静态定制）。
         */
        public Builder defaults(Function<Map<String, Object>, SerialCommConfigSchema> defaults) {
            this.defaults = defaults;
            return this;
        }

        public SerialCommFlow build() {
            return new SerialCommFlow(this);
        }
    }
}
