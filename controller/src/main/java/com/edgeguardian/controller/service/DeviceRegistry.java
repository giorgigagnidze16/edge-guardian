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
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed device registry (Phase 2).
 * Replaces the Phase 1 in-memory ConcurrentHashMap implementation
 * with JPA repositories backed by PostgreSQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistry {

    private final DeviceRepository deviceRepository;
    private final DeviceManifestRepository manifestRepository;
    private final DeviceTelemetryRepository telemetryRepository;

    /**
     * Register a new device or update an existing one.
     *
     * @return the registered (or re-registered) device
     */
    @Transactional
    public Device register(Long organizationId, String deviceId, String hostname,
                           String architecture, String os, String agentVersion) {
        Optional<Device> existing = deviceRepository.findByDeviceId(deviceId);
        if (existing.isPresent()) {
            Device device = existing.get();
            log.info("Device re-registered: {}", deviceId);
            device.setOrganizationId(organizationId);
            device.setHostname(hostname);
            device.setArchitecture(architecture);
            device.setOs(os);
            device.setAgentVersion(agentVersion);
            device.setLastHeartbeat(Instant.now());
            device.setState(Device.DeviceState.ONLINE);
            return deviceRepository.save(device);
        }

        Device device = new Device(deviceId, hostname, architecture, os, agentVersion);
        device.setOrganizationId(organizationId);
        device = deviceRepository.save(device);
        log.info("New device registered: {} ({}@{})", deviceId, architecture, os);
        return device;
    }

    @Transactional
    public Device register(Long organizationId, String deviceId, String hostname,
                           String architecture, String os, String agentVersion,
                           Map<String, String> labels) {
        Device device = register(organizationId, deviceId, hostname, architecture, os, agentVersion);
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
     * Get latest telemetry status for all devices in one organization.
     */
    @Transactional(readOnly = true)
    public Map<String, DeviceStatus> getLatestStatusForOrganization(Long organizationId) {
        Map<String, DeviceStatus> result = new HashMap<>();
        for (DeviceTelemetry t : telemetryRepository.findLatestForOrganization(organizationId)) {
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
     * Find a device by its logical device ID, scoped to one organization.
     * Returns empty if the device exists but belongs to a different org — callers
     * should surface this as 404 (not 403) to avoid leaking existence across tenants.
     */
    @Transactional(readOnly = true)
    public Optional<Device> findByIdForOrganization(String deviceId, Long organizationId) {
        return deviceRepository.findByDeviceIdAndOrganizationId(deviceId, organizationId);
    }

    /**
     * List devices belonging to one organization.
     */
    @Transactional(readOnly = true)
    public List<Device> findByOrganizationId(Long organizationId) {
        return deviceRepository.findByOrganizationId(organizationId);
    }

    /**
     * Count devices for a single organization. Used by dashboard fleet widgets.
     */
    @Transactional(readOnly = true)
    public long countByOrganizationId(Long organizationId) {
        return deviceRepository.countByOrganizationId(organizationId);
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
        deviceRepository.findByDeviceId(deviceId)
                .ifPresent(d -> entity.setOrganizationId(d.getOrganizationId()));
        DeviceManifestEntity saved = manifestRepository.save(entity);
        log.info("Manifest created for device {}, version {}", deviceId, saved.getVersion());
        return saved;
    }
}
