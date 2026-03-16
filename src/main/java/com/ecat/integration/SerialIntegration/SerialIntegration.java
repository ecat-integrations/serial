package com.ecat.integration.SerialIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.ecat.core.Integration.IntegrationBase;

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

    @Override
    public void onStart() {
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onRelease() {
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
     * If the port is already registered with different settings (baudrate, dataBits, etc.),
     * throws IllegalArgumentException to prevent silent configuration conflicts.
     */
    public SerialSource register(SerialInfo serialInfo, String identity) {
        SerialSourcePort existingPort = serialPorts.get(serialInfo.portName);
        if (existingPort != null) {
            if (existingPort.serialInfo.settingsMatch(serialInfo)) {
                // Same settings — reuse the existing port
                return new SerialSource(existingPort, identity);
            } else {
                // Settings conflict — throw to prevent silent misconfiguration
                log.warn("Port conflict: {} already registered with [{}], requested [{}] by identity={}",
                        serialInfo.portName,
                        existingPort.serialInfo.settingsDescription(),
                        serialInfo.settingsDescription(),
                        identity);
                throw new IllegalArgumentException(
                        "Port '" + serialInfo.portName + "' already registered with different settings. "
                        + "Existing: [" + existingPort.serialInfo.settingsDescription() + "], "
                        + "Requested: [" + serialInfo.settingsDescription() + "]");
            }
        }
        SerialSourcePort port = new SerialSourcePort(serialInfo, 1, this);
        serialPorts.put(serialInfo.portName, port);
        return new SerialSource(port, identity);
    }

    /**
     * Remove a port from the map. Called by SerialSourcePort when last source unregisters.
     */
    void removePort(String portName) {
        serialPorts.remove(portName);
        log.info("Removed port from map: " + portName + ", remaining ports: " + serialPorts.size());
    }
}
