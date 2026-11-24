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
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;
import com.ecat.integration.SerialIntegration.SerialTransport;
import com.ecat.integration.TcpIntegration.ConfigSchemas.TcpClientCommConfigSchema;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 串口通讯子 flow（两步形态）：transport_select（serial/tcp 二选一入口步）→
 * comm_config（按档表单）→ 落盘 comm_settings = {transport, …该档字段…}
 * （落盘形状对存量 entry additive，parse 已按档读）。
 *
 * <p>分档表单：serial 档渲染 {@link SerialCommConfigSchema}（7 个 UART 字段，不含
 * host/port，也不含 transport——通道已在上一步选定）；tcp 档<b>整块复用</b> tcp 仓
 * {@link TcpClientCommConfigSchema}（不摘字段、不改 required、不设默认端口，tcp 升级
 * schema 本仓零修改）。两档校验同源：serial 档经 validateForTransport（COM 档执法与
 * 既有逐字一致），tcp 档由 tcp schema 原生 validate 承担。
 *
 * <p>供设备集成宿主 flow 挂载，入口由本类 registerStepEntry 显式声明（宿主只认
 * registerFlowStep 返回值，不点名内部 stepId），通道承载演进对宿主零修改。存量 COM
 * entry 重配：transport_select 预填 entry 现有 transport（缺省 serial，与 parse 同源），
 * 直接下一步即回既有档位。设备侧读出经
 * {@link com.ecat.integration.SerialIntegration.SerialCommSettings#parse}（唯一合法通道）。
 *
 * @author coffee
 */
public class SerialCommFlow extends AbstractSubConfigFlow {

    private static final String STEP_TRANSPORT_SELECT = "transport_select";
    private static final String STEP_COMM_CONFIG = "comm_config";

    private final SerialCommConfigSchema serialSchema;

    /**
     * serial 档动态预填 hook（两级默认值 API，§3.3）：进 comm 步渲染表单前按宿主
     * entryData 求值，返回值仅决定字段预填/枚举选项——不改变校验规则（执法恒为
     * createSchema().validate）。契约：hook 抛异常原样炸到 flow 层（不吞、不转 errors）；
     * 返回 null 视为 hook 违约抛 IllegalStateException（不兜底）；与静态 schema 并设时
     * 本 hook 优先。仅 serial 档求值（tcp 档走 {@link #defaultsTcp}）。
     */
    private final Function<Map<String, Object>, SerialCommConfigSchema> defaults;

    /**
     * tcp 档动态预填 hook：仅 tcp 档求值，返回 tcp schema 的 builder 产物定制预填
     * （host/port/超时族）。契约同 {@link #defaults}。与 defaults 分立两个 hook 的取舍：
     * 两档 schema 类型不同（SerialCommConfigSchema / TcpClientCommConfigSchema），单
     * hook 需退化到 Object 丢失强类型，故按档分立——flow 在 transport_select 之后按档
     * 派发，hook 本身无需（也无法）看到本次会话的通道选择。
     */
    private final Function<Map<String, Object>, TcpClientCommConfigSchema> defaultsTcp;

    /** 无参构造 = 库标准默认（最简挂载形态）。 */
    public SerialCommFlow() {
        this.serialSchema = new SerialCommConfigSchema();
        this.defaults = null;
        this.defaultsTcp = null;
        registerSteps();
    }

    private SerialCommFlow(Builder b) {
        this.serialSchema = b.serialSchema;
        this.defaults = b.defaults;
        this.defaultsTcp = b.defaultsTcp;
        registerSteps();
    }

    private void registerSteps() {
        // 入口步=通道选择（serial/tcp 二选一）；comm_config 按档渲染对应表单
        registerStepEntry(STEP_TRANSPORT_SELECT, this::stepTransportSelect, "传输通道");
        registerStep(STEP_COMM_CONFIG, this::stepCommConfig, "通讯配置");
    }

    // ========== 步 1：transport_select（通道选择） ==========

    private ConfigFlowResult stepTransportSelect(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm(STEP_TRANSPORT_SELECT, transportSelectSchema(), new HashMap<>());
        }
        Map<String, Object> errors = transportSelectSchema().validate(userInput);
        if (!errors.isEmpty()) {
            return showForm(STEP_TRANSPORT_SELECT, transportSelectSchema(), errors);
        }
        // 选择已由 core 存 stepInputs（comm_config 从那里读档）；同一习语进下一步
        return handleStep(STEP_COMM_CONFIG, null);
    }

    /**
     * transport_select 表单：serial/tcp 二选一（EnumConfigItem 成员资格表单期执法）。
     * 预填优先级：存量 entry comm_settings.transport（reconfigure 存量 entry 直接下一步，
     * core 在 reconfigure 启动时预载 entryData）→ 库标准缺省 serial（与 parse 缺省同源）。
     * 同会话重显由 core step_inputs 覆盖（用户已选值优先）。
     */
    private ConfigSchema transportSelectSchema() {
        return new ConfigSchema()
            .addField(new EnumConfigItem("transport", true, prefilledTransport())
                .displayName("传输通道")
                .addOptions(SerialTransport.toMap())
                .buildValidator());
    }

    /** 存量 entry transport 预填（非法/缺失值一律落回库标准缺省 serial，预填语义不执法）。 */
    private String prefilledTransport() {
        Object comm = context.getEntryData().get("comm_settings");
        if (comm instanceof Map) {
            Object transport = ((Map<?, ?>) comm).get("transport");
            if (transport != null) {
                String value = String.valueOf(transport).trim();
                if (SerialTransport.SERIAL.getValue().equals(value)
                        || SerialTransport.TCP.getValue().equals(value)) {
                    return value;
                }
            }
        }
        return SerialTransport.SERIAL.getValue();
    }

    // ========== 步 2：comm_config（按档表单） ==========

    private ConfigFlowResult stepCommConfig(Map<String, Object> userInput) {
        SerialTransport transport = selectedTransport();
        if (userInput == null || userInput.isEmpty()) {
            return showForm(STEP_COMM_CONFIG, commSchemaFor(transport), new HashMap<>());
        }
        Map<String, Object> errors = validateFor(transport, userInput);
        if (!errors.isEmpty()) {
            return showForm(STEP_COMM_CONFIG, commSchemaFor(transport), errors);
        }
        // 落盘形状：comm_settings = {transport, …该档字段…}——transport_select 的选择并入
        // userInput 前置；serial 档表单本身不含 transport 键，tcp 档表单亦然（通道是入口步收集的）
        Map<String, Object> commSettings = new LinkedHashMap<>();
        commSettings.put("transport", transport.getValue());
        commSettings.putAll(userInput);
        context.getEntryData().put("comm_settings", commSettings);
        return subFlowComplete();   // 出口：交回宿主尾步（挂载时由宿主指定）
    }

    /**
     * comm_config 当前档：读 transport_select 已提交的选择（core 在 handler 调用前存
     * stepInputs）。严格模式：transport_select 未选（越步直投 comm_config）抛
     * IllegalStateException——入口步是两步形态的唯一档位来源，不猜缺省档。
     */
    private SerialTransport selectedTransport() {
        Object choice = context.getStepInputs().get(STEP_TRANSPORT_SELECT);
        if (!(choice instanceof Map) || ((Map<?, ?>) choice).get("transport") == null) {
            throw new IllegalStateException(
                    "comm_config 前须先提交 transport_select（入口步=传输通道选择，不得越步直投）");
        }
        return SerialTransport.fromValue(String.valueOf(((Map<?, ?>) choice).get("transport")));
    }

    /** 按档表单 schema：serial=本库 7 UART 字段；tcp=整块复用 tcp 仓 schema。 */
    private ConfigSchema commSchemaFor(SerialTransport transport) {
        return transport == SerialTransport.TCP
                ? resolveTcpSchema().createSchema()
                : resolveCommSchema().createSchema();
    }

    /** 按档提交校验：tcp 由 tcp schema 原生 validate 承担；serial 走既有执法入口不变。 */
    private Map<String, Object> validateFor(SerialTransport transport, Map<String, Object> userInput) {
        if (transport == SerialTransport.TCP) {
            return resolveTcpSchema().createSchema().validate(userInput);
        }
        return resolveCommSchema().validateForTransport(userInput);
    }

    /**
     * serial 档表单 schema 解析（两级默认值求值点）：defaults hook 优先（按挂载时注入的
     * 宿主 context.entryData 动态预填），未设 hook 落到静态 schema（Builder.schema 定制，
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

    /** tcp 档 schema 解析：defaultsTcp hook 优先；未设=标准 tcp schema（整块原样）。 */
    private TcpClientCommConfigSchema resolveTcpSchema() {
        if (defaultsTcp != null) {
            TcpClientCommConfigSchema resolved = defaultsTcp.apply(context.getEntryData());
            if (resolved == null) {
                throw new IllegalStateException(
                        "SerialCommFlow defaultsTcp hook 返回 null（契约：返回 TcpClientCommConfigSchema.builder() 产物）");
            }
            return resolved;
        }
        return new TcpClientCommConfigSchema();
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
        private Function<Map<String, Object>, TcpClientCommConfigSchema> defaultsTcp;

        /** serial 档定制预填（波特率/超时等）：传 SerialCommConfigSchema.builder() 构建的实例。 */
        public Builder schema(SerialCommConfigSchema serialSchema) {
            this.serialSchema = serialSchema;
            return this;
        }

        /**
         * serial 档动态预填：进 comm 步（serial 档）渲染表单前按宿主 entryData 求值。
         * 契约：hook 只决定字段默认值/枚举选项，不改变校验规则；抛异常炸到 flow 层不兜底；
         * 用户已填值永远优先（core 以 step_inputs[stepId] 回显，前端盖过 schema 预填）。
         * 与 {@link #schema} 同时设置时本 hook 优先（动态预填覆盖静态定制）。
         */
        public Builder defaults(Function<Map<String, Object>, SerialCommConfigSchema> defaults) {
            this.defaults = defaults;
            return this;
        }

        /** tcp 档动态预填：进 comm 步（tcp 档）渲染表单前按宿主 entryData 求值，契约同 defaults。 */
        public Builder defaultsTcp(Function<Map<String, Object>, TcpClientCommConfigSchema> defaultsTcp) {
            this.defaultsTcp = defaultsTcp;
            return this;
        }

        public SerialCommFlow build() {
            return new SerialCommFlow(this);
        }
    }
}
