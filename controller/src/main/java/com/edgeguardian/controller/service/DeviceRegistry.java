package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.model.DeviceTelemetry;
import com.edgeguardian.controller.repository.DeviceManifestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.DeviceTelemetryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed device registry (Phase 2).
 * Replaces the Phase 1 in-memory ConcurrentHashMap implementation
 * with JPA repositories backed by PostgreSQL.
 */
@Service
@RequiredArgsConstructor
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final DeviceRepository deviceRepository;
    private final DeviceManifestRepository manifestRepository;
    private final DeviceTelemetryRepository telemetryRepository;

    /**
     * Register a new device or update an existing one.
     *
     * @return the registered (or re-registered) device
     */
    @Transactional
    public Device register(String deviceId, String hostname, String architecture,
                           String os, String agentVersion) {
        Optional<Device> existing = deviceRepository.findByDeviceId(deviceId);
        if (existing.isPresent()) {
            Device device = existing.get();
            log.info("Device re-registered: {}", deviceId);
            device.setHostname(hostname);
            device.setArchitecture(architecture);
            device.setOs(os);
            device.setAgentVersion(agentVersion);
            device.setLastHeartbeat(Instant.now());
            device.setState(Device.DeviceState.ONLINE);
            return deviceRepository.save(device);
        }

        Device device = new Device(deviceId, hostname, architecture, os, agentVersion);
        device = deviceRepository.save(device);
        log.info("New device registered: {} ({}@{})", deviceId, architecture, os);
        return device;
    }

    /**
     * Register a device with labels. Used by the agent registration endpoint.
     */
    @Transactional
    public Device register(String deviceId, String hostname, String architecture,
                           String os, String agentVersion, Map<String, String> labels) {
        Device device = register(deviceId, hostname, architecture, os, agentVersion);
        if (labels != null && !labels.isEmpty()) {
            device.getLabels().putAll(labels);
            device = deviceRepository.save(device);
        }
        return device;
    }

    /**
     * Update heartbeat timestamp and insert telemetry for a device.
     */
    @Transactional
    public Optional<Device> heartbeat(String deviceId, DeviceStatus status) {
        Optional<Device> opt = deviceRepository.findByDeviceId(deviceId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        Device device = opt.get();
        device.setLastHeartbeat(Instant.now());
        device.setState(Device.DeviceState.ONLINE);
        deviceRepository.save(device);

        if (status != null) {
            DeviceTelemetry telemetry = DeviceTelemetry.from(deviceId, device.getOrganizationId(), status);
            telemetryRepository.save(telemetry);
        }

        return Optional.of(device);
    }

    /**
     * Get latest telemetry status for a single device.
     */
    @Transactional(readOnly = true)
    public Optional<DeviceStatus> getLatestStatus(String deviceId) {
        return telemetryRepository.findLatestByDeviceId(deviceId)
            .map(DeviceTelemetry::toDeviceStatus);
    }

    /**
     * Get latest telemetry status for all devices, keyed by deviceId.
     */
    @Transactional(readOnly = true)
    public Map<String, DeviceStatus> getLatestStatusForAllDevices() {
        Map<String, DeviceStatus> result = new HashMap<>();
        for (DeviceTelemetry t : telemetryRepository.findLatestForAllDevices()) {
            result.put(t.getDeviceId(), t.toDeviceStatus());
        }
        return result;
    }

    /**
     * Find a device by its logical device ID.
     */
    @Transactional(readOnly = true)
    public Optional<Device> findById(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    /**
     * List all registered devices.
     */
    @Transactional(readOnly = true)
    public List<Device> findAll() {
        return deviceRepository.findAll();
    }

    /**
     * Remove a device and its manifest.
     */
    @Transactional
    public boolean remove(String deviceId) {
        if (!deviceRepository.existsByDeviceId(deviceId)) {
            return false;
        }
        manifestRepository.deleteByDeviceId(deviceId);
        deviceRepository.deleteByDeviceId(deviceId);
        log.info("Device removed: {}", deviceId);
        return true;
    }

    /**
     * Return the total number of registered devices.
     */
    @Transactional(readOnly = true)
    public long count() {
        return deviceRepository.count();
    }

    // --- Manifest operations ---

    /**
     * Retrieve the manifest for a device, if one exists.
     */
    @Transactional(readOnly = true)
    public Optional<DeviceManifestEntity> getManifest(String deviceId) {
        return manifestRepository.findByDeviceId(deviceId);
    }

    /**
     * Save or update the manifest for a device.
     * Increments the version number on update.
     */
    @Transactional
    public DeviceManifestEntity saveManifest(String deviceId,
                                             Map<String, Object> metadata,
                                             Map<String, Object> spec) {
        Optional<DeviceManifestEntity> existing = manifestRepository.findByDeviceId(deviceId);
        if (existing.isPresent()) {
            DeviceManifestEntity entity = existing.get();
            entity.setMetadata(metadata);
            entity.setSpec(spec);
            entity.setVersion(entity.getVersion() + 1);
            log.info("Manifest updated for device {}, version {}", deviceId, entity.getVersion());
            return manifestRepository.save(entity);
        }

        DeviceManifestEntity entity = new DeviceManifestEntity(deviceId, metadata, spec);
        entity = manifestRepository.save(entity);
        log.info("Manifest created for device {}, version {}", deviceId, entity.getVersion());
        return entity;
    }
}
