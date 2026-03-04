package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.*;
import com.edgeguardian.controller.mqtt.CommandPublisher;
import com.edgeguardian.controller.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OTAService {

    private final OtaArtifactRepository artifactRepository;
    private final OtaDeploymentRepository deploymentRepository;
    private final DeploymentDeviceStatusRepository deviceStatusRepository;
    private final DeviceRepository deviceRepository;
    private final CommandPublisher commandPublisher;

    // --- Artifacts ---

    @Transactional
    public OtaArtifact createArtifact(Long orgId, String name, String version,
                                       String architecture, long size, String sha256,
                                       String ed25519Sig, String s3Key, Long createdBy) {
        return artifactRepository.save(OtaArtifact.builder()
                .organizationId(orgId)
                .name(name).version(version).architecture(architecture)
                .size(size).sha256(sha256).ed25519Sig(ed25519Sig).s3Key(s3Key)
                .createdBy(createdBy)
                .build());
    }

    @Transactional(readOnly = true)
    public List<OtaArtifact> listArtifacts(Long orgId) {
        return artifactRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional(readOnly = true)
    public OtaArtifact getArtifact(Long artifactId) {
        return artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found"));
    }

    @Transactional
    public void deleteArtifact(Long artifactId) {
        artifactRepository.deleteById(artifactId);
    }

    // --- Deployments ---

    @Transactional
    public OtaDeployment createDeployment(Long orgId, Long artifactId, String strategy,
                                           Map<String, String> labelSelector, Long createdBy) {
        var artifact = getArtifact(artifactId);

        var deployment = deploymentRepository.save(OtaDeployment.builder()
                .organizationId(orgId)
                .artifactId(artifactId)
                .strategy(strategy != null ? strategy : "rolling")
                .labelSelector(labelSelector != null ? labelSelector : Map.of())
                .createdBy(createdBy)
                .build());

        var targetDevices = deviceRepository.findAll().stream()
                .filter(d -> orgId.equals(d.getOrganizationId()))
                .filter(d -> matchesLabels(d, labelSelector))
                .toList();

        var downloadUrl = "/api/v1/agent/ota/artifacts/" + artifact.getId() + "/download";

        for (Device device : targetDevices) {
            deviceStatusRepository.save(DeploymentDeviceStatus.builder()
                    .deploymentId(deployment.getId())
                    .deviceId(device.getDeviceId())
                    .build());

            publishOtaCommand(device.getDeviceId(), deployment.getId(), artifact, downloadUrl);
        }

        if (!targetDevices.isEmpty()) {
            deployment.setState("in_progress");
            deployment = deploymentRepository.save(deployment);
        }

        log.info("OTA deployment {} created: {} target devices, artifact={} v{}",
                deployment.getId(), targetDevices.size(), artifact.getName(), artifact.getVersion());
        return deployment;
    }

    @Transactional(readOnly = true)
    public List<OtaDeployment> listDeployments(Long orgId) {
        return deploymentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional(readOnly = true)
    public OtaDeployment getDeployment(Long deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
    }

    @Transactional(readOnly = true)
    public List<DeploymentDeviceStatus> getDeploymentDeviceStatuses(Long deploymentId) {
        return deviceStatusRepository.findByDeploymentId(deploymentId);
    }

    // --- Status ---

    @Transactional
    public void updateDeviceOtaStatus(Long deploymentId, String deviceId,
                                       String state, int progress, String errorMessage) {
        var status = deviceStatusRepository
                .findByDeploymentIdAndDeviceId(deploymentId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Device status not found for deployment"));

        status.setState(state);
        status.setProgress(progress);
        if (errorMessage != null) status.setErrorMessage(errorMessage);
        if ("downloading".equals(state) && status.getStartedAt() == null) {
            status.setStartedAt(Instant.now());
        }
        if (Set.of("completed", "failed", "rolled_back").contains(state)) {
            status.setCompletedAt(Instant.now());
        }
        deviceStatusRepository.save(status);

        checkDeploymentCompletion(deploymentId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingOtaCommands(String deviceId) {
        var pendingStatuses = deviceStatusRepository.findByDeviceIdAndState(deviceId, "pending");
        List<Map<String, Object>> commands = new ArrayList<>();

        for (var status : pendingStatuses) {
            var artifact = artifactRepository.findById(
                    deploymentRepository.findById(status.getDeploymentId())
                            .map(OtaDeployment::getArtifactId).orElse(-1L));
            if (artifact.isEmpty()) continue;

            var a = artifact.get();
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("id", UUID.randomUUID().toString());
            cmd.put("type", "ota_update");
            cmd.put("params", Map.of(
                    "deployment_id", String.valueOf(status.getDeploymentId()),
                    "artifact_name", a.getName(),
                    "artifact_version", a.getVersion(),
                    "sha256", a.getSha256(),
                    "ed25519_sig", a.getEd25519Sig() != null ? a.getEd25519Sig() : "",
                    "download_url", "/api/v1/agent/ota/artifacts/" + a.getId() + "/download"
            ));
            cmd.put("createdAt", Instant.now().toString());
            commands.add(cmd);
        }

        return commands;
    }

    // --- Private helpers ---

    private void checkDeploymentCompletion(Long deploymentId) {
        var statuses = deviceStatusRepository.findByDeploymentId(deploymentId);
        var terminalStates = Set.of("completed", "failed", "rolled_back");

        boolean allDone = statuses.stream().allMatch(s -> terminalStates.contains(s.getState()));
        if (allDone && !statuses.isEmpty()) {
            boolean anyFailed = statuses.stream()
                    .anyMatch(s -> "failed".equals(s.getState()) || "rolled_back".equals(s.getState()));
            deploymentRepository.findById(deploymentId).ifPresent(deployment -> {
                deployment.setState(anyFailed ? "failed" : "completed");
                deploymentRepository.save(deployment);
            });
        }
    }

    private void publishOtaCommand(String deviceId, Long deploymentId,
                                    OtaArtifact artifact, String downloadUrl) {
        try {
            commandPublisher.publishCommand(deviceId, "ota_update", Map.of(
                    "deployment_id", String.valueOf(deploymentId),
                    "artifact_name", artifact.getName(),
                    "artifact_version", artifact.getVersion(),
                    "sha256", artifact.getSha256(),
                    "ed25519_sig", artifact.getEd25519Sig() != null ? artifact.getEd25519Sig() : "",
                    "download_url", downloadUrl
            ));
        } catch (MqttException e) {
            log.error("Failed to publish OTA command to device {}: {}", deviceId, e.getMessage());
        }
    }

    private boolean matchesLabels(Device device, Map<String, String> selector) {
        if (selector == null || selector.isEmpty()) return true;
        var labels = device.getLabels();
        if (labels == null) return false;
        return selector.entrySet().stream()
                .allMatch(e -> e.getValue().equals(labels.get(e.getKey())));
    }
}