package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.service.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for agent-to-controller communication.
 * Separate from DeviceController (dashboard API) to keep concerns clean.
 *
 * Endpoints:
 *   POST /api/v1/agent/register         - Agent registration
 *   POST /api/v1/agent/heartbeat        - Periodic heartbeat
 *   GET  /api/v1/agent/desired-state/{deviceId} - Fetch desired state
 *   POST /api/v1/agent/report-state     - Report observed state
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentApiController {

    private static final Logger log = LoggerFactory.getLogger(AgentApiController.class);

    private final DeviceRegistry registry;

    public AgentApiController(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Register a device with the controller.
     * If the device already exists, it is re-registered (updated).
     * Returns an initial manifest if one has been configured for the device.
     */
    @PostMapping("/register")
    public ResponseEntity<AgentRegisterResponse> register(@RequestBody AgentRegisterRequest request) {
        log.info("Agent register: deviceId={}, arch={}, os={}",
                request.deviceId(), request.architecture(), request.os());

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new AgentRegisterResponse(false, "deviceId is required", null));
        }

        Device device = registry.register(
                request.deviceId(),
                request.hostname(),
                request.architecture(),
                request.os(),
                request.agentVersion(),
                request.labels()
        );

        // Check if there is a manifest for this device.
        Map<String, Object> manifestMap = null;
        Optional<DeviceManifestEntity> manifest = registry.getManifest(device.getDeviceId());
        if (manifest.isPresent()) {
            manifestMap = toManifestMap(manifest.get());
        }

        return ResponseEntity.ok(new AgentRegisterResponse(
                true,
                "Device registered successfully",
                manifestMap
        ));
    }

    /**
     * Process a heartbeat from an agent.
     * Updates the device's last-seen timestamp and status.
     * Returns a manifest update if the stored manifest version is newer
     * than what the agent has seen.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<AgentHeartbeatResponse> heartbeat(@RequestBody AgentHeartbeatRequest request) {
        log.debug("Agent heartbeat: deviceId={}", request.deviceId());

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Parse status from the loosely-typed map into a DeviceStatus.
        DeviceStatus status = parseDeviceStatus(request.status());
        Optional<Device> deviceOpt = registry.heartbeat(request.deviceId(), status);

        if (deviceOpt.isEmpty()) {
            // Device not registered; agent should re-register.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new AgentHeartbeatResponse(false, null, List.of()));
        }

        // Check for manifest updates.
        boolean manifestUpdated = false;
        Map<String, Object> manifestMap = null;
        Optional<DeviceManifestEntity> manifest = registry.getManifest(request.deviceId());
        if (manifest.isPresent()) {
            // Always return the manifest on heartbeat so the agent can detect changes.
            // The agent compares versions locally.
            manifestUpdated = true;
            manifestMap = toManifestMap(manifest.get());
        }

        return ResponseEntity.ok(new AgentHeartbeatResponse(
                manifestUpdated,
                manifestMap,
                List.of() // No pending commands in Phase 2.
        ));
    }

    /**
     * Return the desired-state manifest for a specific device.
     */
    @GetMapping("/desired-state/{deviceId}")
    public ResponseEntity<AgentDesiredStateResponse> getDesiredState(@PathVariable String deviceId) {
        log.debug("Agent get desired state: deviceId={}", deviceId);

        Optional<DeviceManifestEntity> manifest = registry.getManifest(deviceId);
        if (manifest.isEmpty()) {
            return ResponseEntity.ok(new AgentDesiredStateResponse(null, 0));
        }

        DeviceManifestEntity entity = manifest.get();
        return ResponseEntity.ok(new AgentDesiredStateResponse(
                toManifestMap(entity),
                entity.getVersion()
        ));
    }

    /**
     * Accept a state report from an agent.
     * Updates the device's runtime metrics in the database.
     */
    @PostMapping("/report-state")
    public ResponseEntity<AgentReportStateResponse> reportState(@RequestBody AgentReportStateRequest request) {
        log.debug("Agent report state: deviceId={}", request.deviceId());

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().body(new AgentReportStateResponse(false));
        }

        DeviceStatus status = parseDeviceStatus(request.status());
        registry.heartbeat(request.deviceId(), status);

        return ResponseEntity.ok(new AgentReportStateResponse(true));
    }

    // --- Helper methods ---

    /**
     * Convert a DeviceManifestEntity to the flat map structure the agent expects.
     * The agent's model.DeviceManifest has: apiVersion, kind, metadata, spec, version.
     */
    private Map<String, Object> toManifestMap(DeviceManifestEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiVersion", entity.getApiVersion());
        map.put("kind", entity.getKind());
        map.put("metadata", entity.getMetadata() != null ? entity.getMetadata() : Map.of());
        map.put("spec", entity.getSpec() != null ? entity.getSpec() : Map.of());
        map.put("version", entity.getVersion());
        return map;
    }

    /**
     * Parse a loosely-typed status map into a DeviceStatus object.
     * Handles the JSON field names from the agent's model.DeviceStatus.
     */
    private DeviceStatus parseDeviceStatus(Map<String, Object> statusMap) {
        if (statusMap == null) {
            return null;
        }

        DeviceStatus status = new DeviceStatus();
        status.setCpuUsagePercent(toDouble(statusMap.get("cpuUsagePercent")));
        status.setMemoryUsedBytes(toLong(statusMap.get("memoryUsedBytes")));
        status.setMemoryTotalBytes(toLong(statusMap.get("memoryTotalBytes")));
        status.setDiskUsedBytes(toLong(statusMap.get("diskUsedBytes")));
        status.setDiskTotalBytes(toLong(statusMap.get("diskTotalBytes")));
        status.setTemperatureCelsius(toDouble(statusMap.get("temperatureCelsius")));
        status.setUptimeSeconds(toLong(statusMap.get("uptimeSeconds")));

        Object lastReconcile = statusMap.get("lastReconcile");
        if (lastReconcile instanceof String s && !s.isEmpty()) {
            try {
                status.setLastReconcile(Instant.parse(s));
            } catch (Exception e) {
                log.debug("Could not parse lastReconcile: {}", s);
            }
        }

        Object reconcileStatus = statusMap.get("reconcileStatus");
        if (reconcileStatus instanceof String s) {
            status.setReconcileStatus(s);
        }

        return status;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
