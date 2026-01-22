package com.ecat.integration.SerialIntegration;

import java.util.HashMap;
import java.util.Map;

import com.ecat.core.Integration.IntegrationBase;

/**
 * SerialIntegration is a class that manages serial port communication
 * and provides methods to register and retrieve serial sources.
 * 
 * @author coffee
 */
public class SerialIntegration extends IntegrationBase {

    // 串口对象 列表
    private Map<String, SerialSource> serialSources = new HashMap<>();

    // 读取配置
    // private Map<String, Object> integrationConfig;


    @Override
    public void onInit() {
        // 读取配置
        // this.integrationConfig = integrationManager.loadConfig(this.getName());

    }

    @Override
    public void onStart() {
        // // 获取串口配置
        // List<Map<String, Object>> portConfigs = (List<Map<String, Object>>) this.integrationConfig.get("ports");
        // if (portConfigs != null) {
        //     for (Map<String, Object> config : portConfigs) {
        //         log.info(config.toString());
        //         // 逐个启动串口 及加载队列
        //         // todo 这里需要根据实际情况进行调整
        //     }
        // }else{
        //     log.error("串口配置为空,加载结束。");
        // }
    }

    @Override
    public void onPause() {

    }

    @Override
    public void onRelease() {

    }

    public SerialSource getSerialSource(String portName) {
        return serialSources.get(portName);
    }

    public SerialSource register(SerialInfo serialInfo, String identity) {
        SerialSource resource = serialSources.computeIfAbsent(serialInfo.portName, k -> new SerialSource(serialInfo));
        resource.registerIntegration(identity);
        return resource;
    }

}
