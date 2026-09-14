package com.ecat.integration.SerialIntegration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

import com.ecat.core.CommTrace.OwnerLevel;
import com.ecat.core.CommTrace.ResourceKind;
import com.ecat.core.CommTrace.ResourceOwner;
import com.ecat.core.CommTrace.ResourceQuery;
import com.ecat.core.CommTrace.ResourceRef;
import com.ecat.core.Device.RemovalHost;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.integration.SerialIntegration.ConfigSchemas.SerialCommConfigSchema;

/**
 * SerialIntegration is a class that manages serial port communication
 * and provides methods to register and retrieve serial sources.
 *
 * <p>资源归属账本（io-resource-owner 设计）：register 家族携带 {@link ResourceOwner}
 * （宿主派生/显式两形态），账本=serialPorts 地图 + 每端口 connectedSources
 * 的 owner 集合（按需折叠派生，与引用计数同源无漂移）；{@link ResourceQuery} 精准查询
 * 契约（§9-10）由本类实现——指定身份一对一查注册 Info、消费方 registerForDevice 一步
 * 得源、反向三折叠运维读面。
 *
 * @author coffee
 */
public class SerialIntegration extends IntegrationBase implements ResourceQuery<SerialInfo, SerialSource> {

    // 串口对象 列表 — maps portName to shared SerialSourcePort。
    // ConcurrentHashMap（共享单例 + onStart 后 4 线程并行 entry-restore）：账本派生读面
    // （findPortByOwner/foldOwners 迭代）与注册/摘港并发写弱一致迭代，不抛 CME；
    // 创建路径原子性由 computeIfAbsent 承担（见 register）
    private final Map<String, SerialSourcePort> serialPorts = new ConcurrentHashMap<>();

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
    protected void onReleaseImpl() {
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
     * host 收口注册（io-resource-owner §5.2 三合一）：owner 从宿主派生（DeviceBase→DEVICE
     * 层 / IntegrationBase→INTEGRATION 层，不靠调用方手拼串）+ {@code host.onRemove(摘账)}
     * 构造期绑定销毁（core LIFO 统一收割，忘了绑定在签名层面不可能）+ 宿主已终态拒绝并
     * 就地回收（EasyHttpClient 同型守卫，不留半活资源）。设备类调用传 this。
     */
    public SerialSource register(SerialInfo serialInfo, RemovalHost host) {
        Objects.requireNonNull(host, "host 不能为 null——register(SerialInfo, host) 从宿主派生归属");
        // 身份校验先于任何资源创建（严格 fail-fast：非身份宿主/设备 entry 缺 entryId 即抛）
        ResourceOwner owner = ResourceOwner.of(host);
        SerialSource source = register(serialInfo, owner);
        try {
            host.onRemove(source::closePort);
        } catch (RejectedExecutionException e) {
            // 宿主已终态：就地回收刚建的视图（末源时随拆港一并清理），再原样上抛
            source.closePort();
            throw e;
        }
        return source;
    }

    /**
     * 带主注册重载（§5.2）：owner 显式携带——库间带主转发（modbus→serial 的 ADAPTER 条目，
     * 生命周期由转发方自管）与测试假宿主两形态的唯一正当无宿主场景。
     *
     * 同口已注册时按新旧 comm 设置 diff 处理（F-34）：timeout-only 变化原位更新、物理参数
     * 变化走 close+reopen 重建、完全一致直接复用（详见
     * {@link SerialSourcePort#applyReconfiguredSettings}）；identity 由 owner 派生
     * （{@link SerialSource#identityOf}，同设备重注册同键）。
     */
    public SerialSource register(SerialInfo serialInfo, ResourceOwner owner) {
        Objects.requireNonNull(owner, "owner 不能为 null——归属身份必填（host 收口重载自动派生）");
        SerialSourcePort existingPort = serialPorts.get(serialInfo.portName);
        if (existingPort != null) {
            existingPort.applyReconfiguredSettings(serialInfo, SerialSource.identityOf(owner));
            return new SerialSource(existingPort, owner);
        }
        // 创建路径原子化：computeIfAbsent 保证同键并发注册只建一个端口对象（旧
        // get→new→put 双方各建一口、后者覆盖前者，先建口脱离地图成泄漏资源）。锁序
        // 安全：lambda 只触新建端口自身的 lifecycleLock（对象未发布无竞争方）；既有
        // [lifecycleLock→removePort] 路径要求端口先入地图，与本处 [地图 bin 锁→新口] 无环
        SerialSourcePort port = serialPorts.computeIfAbsent(serialInfo.portName,
                k -> new SerialSourcePort(serialInfo, 1, this));
        return new SerialSource(port, owner);
    }

    /** 测试访问口（package-private）：按端口名取共享端口对象。 */
    SerialSourcePort serialPortsGet(String portName) {
        return serialPorts.get(portName);
    }

    /** 测试注入口（package-private）：预置共享端口对象（跳过真实 openPort，注入 mock 串口用）。 */
    void serialPortsPutForTest(String portName, SerialSourcePort port) {
        serialPorts.put(portName, port);
    }

    // ========== ResourceQuery<SerialInfo, SerialSource>（io-resource-owner §9-10 精准查询契约） ==========

    /** 视图的登记 owner（账本读面统一入口）：identity 串注册形态按原串包 LEGACY owner。 */
    private static ResourceOwner registeredOwnerOf(SerialSource source) {
        ResourceOwner owner = source.getOwner();
        return owner != null ? owner : ResourceOwner.legacy(source.getIdentity());
    }

    /**
     * 按身份匹配遍历账本（端口地图 × 每端口 connectedSources）找唯一命中端口。
     * 精准一对一语义（§9-10）：未注册如实 null；同一身份命中多笔资源属异常形态，明确抛
     * （不猜不取首笔）；查询是账本派生读面（活配置——RECONFIGURE 原位替换后即新值）。
     */
    private SerialSourcePort findPortByOwner(OwnerLevel level, String coordinate, String entryId, String deviceId) {
        SerialSourcePort hit = null;
        for (SerialSourcePort port : serialPorts.values()) {
            for (SerialSource source : port.getConnectedSources()) {
                ResourceOwner owner = source.getOwner();
                if (owner == null || owner.getLevel() != level
                        || !owner.getCoordinate().equals(coordinate)) {
                    continue;
                }
                // INTEGRATION 层无 entry 维度；ENTRY/DEVICE 层精确匹配 entryId（+deviceId）
                if (level != OwnerLevel.INTEGRATION && !owner.getEntryId().equals(entryId)) {
                    continue;
                }
                if (level == OwnerLevel.DEVICE && !owner.getDeviceId().equals(deviceId)) {
                    continue;
                }
                if (hit != null && hit != port) {
                    throw new IllegalStateException("身份命中多笔资源（异常形态，明确异常不猜）: level="
                            + level + ", coordinate=" + coordinate + ", entryId=" + entryId
                            + ", deviceId=" + deviceId + " 同时命中 " + hit.getPortName()
                            + " 与 " + port.getPortName());
                }
                hit = port;
            }
        }
        return hit;
    }

    @Override
    public SerialInfo getIntegrationInfo(String coordinate) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        SerialSourcePort port = findPortByOwner(OwnerLevel.INTEGRATION, coordinate, null, null);
        return port != null ? port.serialInfo : null;
    }

    @Override
    public SerialInfo getEntryInfo(String coordinate, String entryId) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        SerialSourcePort port = findPortByOwner(OwnerLevel.ENTRY, coordinate, entryId, null);
        return port != null ? port.serialInfo : null;
    }

    @Override
    public SerialInfo getDeviceInfo(String coordinate, String entryId, String deviceId) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        Objects.requireNonNull(deviceId, "deviceId 不能为 null");
        // ADAPTER 条目同样命中（RTU 设备在 serial 账本即其串口参数，§4 借用带主）
        SerialSourcePort port = findPortByOwner(OwnerLevel.DEVICE, coordinate, entryId, deviceId);
        return port != null ? port.serialInfo : null;
    }

    @Override
    public SerialSource registerForDevice(String coordinate, String entryId, String deviceId,
            ResourceOwner owner, RemovalHost host) {
        Objects.requireNonNull(coordinate, "coordinate 不能为 null");
        Objects.requireNonNull(entryId, "entryId 不能为 null");
        Objects.requireNonNull(deviceId, "deviceId 不能为 null");
        Objects.requireNonNull(owner, "owner 不能为 null——消费方自己的注册身份");
        Objects.requireNonNull(host, "host 不能为 null——生命周期锚点");
        SerialSourcePort port = findPortByOwner(OwnerLevel.DEVICE, coordinate, entryId, deviceId);
        if (port == null) {
            // 未注册如实 null：不凭 Info 另开资源（杜绝查注间隙设备已摘的半死挂靠，§9-10①）
            return null;
        }
        // 原子挂靠：lifecycleLock 内复查退役门（设备恰在此间隙被摘 → null 同未注册语义）
        SerialSource source = port.attachSourceForDevice(owner);
        if (source == null) {
            return null;
        }
        try {
            host.onRemove(source::closePort);
        } catch (RejectedExecutionException e) {
            // 宿主已终态：就地回收（消费方源摘回；末源时随拆港），再原样上抛
            source.closePort();
            throw e;
        }
        return source;
    }

    /** 反向折叠的端口定位：serial 账本只认 SERIAL_PORT ref，其余属调用方错误。 */
    private SerialSourcePort portOfRef(ResourceRef ref) {
        Objects.requireNonNull(ref, "ref 不能为 null");
        if (ref.getKind() != ResourceKind.SERIAL_PORT) {
            throw new IllegalArgumentException("serial 账本只认 SERIAL_PORT ref，实为 " + ref.getKind());
        }
        return serialPorts.get(ref.getKey());
    }

    @Override
    public List<ResourceOwner> getDeviceOwners(ResourceRef ref) {
        SerialSourcePort port = portOfRef(ref);
        Map<String, ResourceOwner> owners = new LinkedHashMap<>();
        if (port != null) {
            for (SerialSource source : port.getConnectedSources()) {
                ResourceOwner owner = registeredOwnerOf(source);
                owners.putIfAbsent(owner.ownerKey(), owner);
            }
        }
        return new ArrayList<>(owners.values());
    }

    @Override
    public List<ResourceOwner> getEntryOwners(ResourceRef ref) {
        return foldOwners(ref, owner -> owner.getLevel() == OwnerLevel.DEVICE
                ? ResourceOwner.entry(owner.getCoordinate(), owner.getEntryId()) : owner);
    }

    @Override
    public List<ResourceOwner> getIntegrationOwners(ResourceRef ref) {
        return foldOwners(ref, owner -> owner.getLevel() == OwnerLevel.LEGACY
                ? owner : ResourceOwner.integration(owner.getCoordinate()));
    }

    /** 折叠加去重读面（运维诊断用）：按 ownerKey 去重保序；LEGACY 无层可折原样透传。 */
    private List<ResourceOwner> foldOwners(ResourceRef ref, UnaryOperator<ResourceOwner> fold) {
        SerialSourcePort port = portOfRef(ref);
        Map<String, ResourceOwner> owners = new LinkedHashMap<>();
        if (port != null) {
            for (SerialSource source : port.getConnectedSources()) {
                ResourceOwner folded = fold.apply(registeredOwnerOf(source));
                owners.putIfAbsent(folded.ownerKey(), folded);
            }
        }
        return new ArrayList<>(owners.values());
    }

    /**
     * Remove a port from the map. Called by SerialSourcePort when last source unregisters.
     */
    void removePort(String portName) {
        serialPorts.remove(portName);
        log.info("Removed port from map: " + portName + ", remaining ports: " + serialPorts.size());
    }
}
