package com.ecat.integration.SerialIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.ecat.core.Integration.IntegrationBase;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

/**
 * SerialIntegration is a class that manages serial port communication
 * and provides methods to register and retrieve serial sources.
 *
 * @author coffee
 */
public class SerialIntegration extends IntegrationBase {

    // 串口对象 列表 — maps portName to shared SerialSourcePort
    private Map<String, SerialSourcePort> serialPorts = new HashMap<>();

    @Override
    public void onInit() {
    }

    /**
     * 启动点（bug-record-20260826-003500 修复挂点）：首次真实端口枚举预热 jSerialComm。
     *
     * <p>时序依据（ecat-core IntegrationManager）：集成加载池是单线程池（fixedThreadPool(1)，
     * IntegrationManager.java:212），按依赖拓扑序串行执行 onLoad→register→onInit→onStart，
     * 依赖方（各串口设备集成）严格后于本集成；awaitTermination 阻塞到全部生命周期完成后才进入
     * loadExistingConfigEntries（entry-restore，GuardedExecutor 4 线程并行）。因此本 onStart 的
     * 单线程枚举必然先于一切并行首触——jSerialComm 的类/包定义与 native 初始化在唯一线程完成，
     * 消除 entry-restore 并发首载在共享父加载器上的 definePackage 竞态；枚举结果进 TTL 缓存，
     * 随后的 entry-restore 突发零重复系统调用。
     *
     * <p>严格模式：预热失败（如 native 库不可用）原样上抛——集成加载失败在 startup-report
     * 可见，优于让每个串口集成在 entry-restore 阶段各自失败。
     */
    @Override
    public void onStart() {
        SerialCommConfigSchema.warmUp();
        log.info("serial 集成启动预热完成：jSerialComm 已加载，端口枚举已入缓存");
    }

    @Override
    public void onPause() {
    }

    /**
     * 释放点：serial 域自持执行资源统一停机（29 号 v2 S1——SDK 完全自持定时与 IO 后，
     * core 停机经集成生命周期收口全部域线程）。
     *
     * <p>顺序：先 {@link SerialSdkTimers}（周期链/超时执法/单发延迟——撤销待发拍并中断
     * 在飞 µs 任务，无长阻塞可等）后 {@link SerialIoPool}（IO drain 池——shutdownNow
     * 中断在飞 jSerialComm 阻塞点；消费集成的设备已先行经 RemovalHost 释放，无在途
     * 事务依赖）。两者幂等且终端态（不自动复活）。
     */
    @Override
    public void onRelease() {
        SerialSdkTimers.shutdown();
        SerialIoPool.shutdown();
    }

    /**
     * Get a SerialSource for the given port name.
     * Returns the first connected source, or null if port not registered.
     */
    public SerialSource getSerialSource(String portName) {
        SerialSourcePort port = serialPorts.get(portName);
        if (port == null) {
            return null;
        }
        List<SerialSource> sources = port.getConnectedSources();
        return sources.isEmpty() ? null : sources.get(0);
    }

    /**
     * Register a serial port and return a new SerialSource for the caller.
     * Multiple calls with the same portName share the underlying SerialSourcePort
     * but each get their own SerialSource instance.
     *
     * 同口已注册时按新旧 comm 设置 diff 处理（F-34）：timeout-only 变化原位更新、物理参数
     * 变化走 close+reopen 重建（RECONFIGURE 改参数即时生效，无需 disable/enable 兜底）、
     * 完全一致直接复用。详见 {@link SerialSourcePort#applyReconfiguredSettings}。
     */
    public SerialSource register(SerialInfo serialInfo, String identity) {
        SerialSourcePort existingPort = serialPorts.get(serialInfo.portName);
        if (existingPort != null) {
            existingPort.applyReconfiguredSettings(serialInfo, identity);
            return new SerialSource(existingPort, identity);
        }
        SerialSourcePort port = new SerialSourcePort(serialInfo, 1, this);
        serialPorts.put(serialInfo.portName, port);
        return new SerialSource(port, identity);
    }

    /** 测试访问口（package-private）：按端口名取共享端口对象。 */
    SerialSourcePort serialPortsGet(String portName) {
        return serialPorts.get(portName);
    }

    /** 测试注入口（package-private）：预置共享端口对象（跳过真实 openPort，注入 mock 串口用）。 */
    void serialPortsPutForTest(String portName, SerialSourcePort port) {
        serialPorts.put(portName, port);
    }

    /**
     * Remove a port from the map. Called by SerialSourcePort when last source unregisters.
     */
    void removePort(String portName) {
        serialPorts.remove(portName);
        log.info("Removed port from map: " + portName + ", remaining ports: " + serialPorts.size());
    }
}
