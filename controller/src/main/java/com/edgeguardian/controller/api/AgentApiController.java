package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.AgentDesiredStateResponse;
import com.edgeguardian.controller.dto.AgentHeartbeatRequest;
import com.edgeguardian.controller.dto.AgentHeartbeatResponse;
import com.edgeguardian.controller.dto.AgentOtaStatusRequest;
import com.edgeguardian.controller.dto.AgentRegisterResponse;
import com.edgeguardian.controller.dto.AgentReportStateRequest;
import com.edgeguardian.controller.dto.AgentReportStateResponse;
import com.edgeguardian.controller.dto.EnrollDeviceRequest;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.service.ArtifactStorageService;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.EnrollmentService;
import com.edgeguardian.controller.service.OTAService;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentApiController {

    private final OTAService otaService;
    private final DeviceRegistry registry;
    private final EnrollmentService enrollmentService;
    private final ArtifactStorageService artifactStorageService;

    @PostMapping("/enroll")
    public ResponseEntity<AgentRegisterResponse> enroll(@RequestBody EnrollDeviceRequest request) {
        log.info("Agent enroll: deviceId={}, token-present={}", request.deviceId(), request.enrollmentToken() != null);

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().body(
                new AgentRegisterResponse(false, "deviceId is required", null, null));
        }
        if (request.enrollmentToken() == null || request.enrollmentToken().isBlank()) {
            return ResponseEntity.badRequest().body(
                new AgentRegisterResponse(false, "enrollmentToken is required", null, null));
        }

        var result = enrollmentService.enrollDevice(
            request.enrollmentToken(), request.deviceId(), request.hostname(),
            request.architecture(), request.os(), request.agentVersion(), request.labels());

        Map<String, Object> manifestMap = registry.getManifest(result.device().getDeviceId())
            .map(this::toManifestMap).orElse(null);

        return ResponseEntity.ok(new AgentRegisterResponse(
            true, "Device enrolled successfully", manifestMap, result.deviceToken()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<AgentHeartbeatResponse> heartbeat(@RequestBody AgentHeartbeatRequest request) {
        log.debug("Agent heartbeat: deviceId={}", request.deviceId());

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        DeviceStatus status = parseDeviceStatus(request.status());
        Optional<Device> deviceOpt = registry.heartbeat(request.deviceId(), status);

        if (deviceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new AgentHeartbeatResponse(false, null, List.of()));
        }

        boolean manifestUpdated = false;
        Map<String, Object> manifestMap = null;
        Optional<DeviceManifestEntity> manifest = registry.getManifest(request.deviceId());
        if (manifest.isPresent()) {
            manifestUpdated = true;
            manifestMap = toManifestMap(manifest.get());
        }

        var pendingCommands = otaService.getPendingOtaCommands(request.deviceId());

        return ResponseEntity.ok(new AgentHeartbeatResponse(manifestUpdated, manifestMap, pendingCommands));
    }

    @GetMapping("/desired-state/{deviceId}")
    public ResponseEntity<AgentDesiredStateResponse> getDesiredState(@PathVariable String deviceId) {
        log.debug("Agent get desired state: deviceId={}", deviceId);

        Optional<DeviceManifestEntity> manifest = registry.getManifest(deviceId);
        if (manifest.isEmpty()) {
            return ResponseEntity.ok(new AgentDesiredStateResponse(null, 0));
        }

        DeviceManifestEntity entity = manifest.get();
        return ResponseEntity.ok(new AgentDesiredStateResponse(toManifestMap(entity), entity.getVersion()));
    }

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

    @GetMapping("/ota/artifacts/{artifactId}/download")
    public ResponseEntity<InputStreamResource> downloadArtifact(@PathVariable Long artifactId) throws IOException {
        var artifact = otaService.getArtifact(artifactId);

        if (artifact.getS3Key() == null || artifact.getS3Key().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        var inputStream = artifactStorageService.load(artifact.getS3Key());
        var size = artifactStorageService.fileSize(artifact.getS3Key());

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(size)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.getName() + "\"")
            .header("X-SHA256", artifact.getSha256())
            .body(new InputStreamResource(inputStream));
    }

    @PostMapping("/ota/status")
    public ResponseEntity<Void> reportOtaStatus(@RequestBody AgentOtaStatusRequest request) {
        log.info("OTA status: deploymentId={}, deviceId={}, state={}, progress={}",
            request.deploymentId(), request.deviceId(), request.state(), request.progress());

        otaService.updateDeviceOtaStatus(
            request.deploymentId(), request.deviceId(),
            request.state(), request.progress(), request.errorMessage());

        return ResponseEntity.ok().build();
    }

    // --- Helpers ---

    private Map<String, Object> toManifestMap(DeviceManifestEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiVersion", entity.getApiVersion());
        map.put("kind", entity.getKind());
        map.put("metadata", entity.getMetadata() != null ? entity.getMetadata() : Map.of());
        map.put("spec", entity.getSpec() != null ? entity.getSpec() : Map.of());
        map.put("version", entity.getVersion());
        return map;
    }

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

        if (statusMap.get("lastReconcile") instanceof String s && !s.isEmpty()) {
            try {
                status.setLastReconcile(Instant.parse(s));
            } catch (Exception e) {
                log.debug("Could not parse lastReconcile: {}", s);
            }
        }
        if (statusMap.get("reconcileStatus") instanceof String s) {
            status.setReconcileStatus(s);
        }

        return status;
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}