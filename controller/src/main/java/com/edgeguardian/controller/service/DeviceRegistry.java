package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory device registry for Phase 1.
 * Will be replaced with a JPA repository backed by PostgreSQL in Phase 2.
 */
@Service
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final ConcurrentMap<String, Device> devices = new ConcurrentHashMap<>();

    /**
     * Register a new device or update an existing one.
     *
     * @return the registered device
     */
    public Device register(String deviceId, String hostname, String architecture,
                           String os, String agentVersion) {
        Device existing = devices.get(deviceId);
        if (existing != null) {
            log.info("Device re-registered: {}", deviceId);
            existing.setHostname(hostname);
            existing.setArchitecture(architecture);
            existing.setOs(os);
            existing.setAgentVersion(agentVersion);
            existing.setLastHeartbeat(Instant.now());
            existing.setState(Device.DeviceState.ONLINE);
            return existing;
        }

        Device device = new Device(deviceId, hostname, architecture, os, agentVersion);
        devices.put(deviceId, device);
        log.info("New device registered: {} ({}@{})", deviceId, architecture, os);
        return device;
    }

    /**
     * Update heartbeat timestamp and status for a device.
     */
    public Optional<Device> heartbeat(String deviceId, DeviceStatus status) {
        Device device = devices.get(deviceId);
        if (device == null) {
            return Optional.empty();
        }

        device.setLastHeartbeat(Instant.now());
        device.setState(Device.DeviceState.ONLINE);
        if (status != null) {
            device.setStatus(status);
        }

        return Optional.of(device);
    }

    public Optional<Device> findById(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public Collection<Device> findAll() {
        return devices.values();
    }

    public boolean remove(String deviceId) {
        return devices.remove(deviceId) != null;
    }

    public int count() {
        return devices.size();
    }
}
