package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.*;
import com.edgeguardian.controller.mqtt.CommandPublisher;
import com.edgeguardian.controller.repository.*;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class OTAService {

    private static final Logger log = LoggerFactory.getLogger(OTAService.class);

    private final OtaArtifactRepository artifactRepository;
    private final OtaDeploymentRepository deploymentRepository;
    private final DeploymentDeviceStatusRepository deviceStatusRepository;
    private final DeviceRepository deviceRepository;
    private final CommandPublisher commandPublisher;

    public OTAService(OtaArtifactRepository artifactRepository,
                      OtaDeploymentRepository deploymentRepository,
                      DeploymentDeviceStatusRepository deviceStatusRepository,
                      DeviceRepository deviceRepository,
                      CommandPublisher commandPublisher) {
        this.artifactRepository = artifactRepository;
        this.deploymentRepository = deploymentRepository;
        this.deviceStatusRepository = deviceStatusRepository;
        this.deviceRepository = deviceRepository;
        this.commandPublisher = commandPublisher;
    }

    // --- Artifacts ---

    @Transactional
    public OtaArtifact createArtifact(Long orgId, String name, String version,
                                       String architecture, long size, String sha256,
                                       String ed25519Sig, String s3Key, Long createdBy) {
        OtaArtifact artifact = OtaArtifact.builder()
                .organizationId(orgId)
                .name(name)
                .version(version)
                .architecture(architecture)
                .size(size)
                .sha256(sha256)
                .ed25519Sig(ed25519Sig)
                .s3Key(s3Key)
                .createdBy(createdBy)
                .build();
        return artifactRepository.save(artifact);
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

    // --- Deployments ---

    @Transactional
    public OtaDeployment createDeployment(Long orgId, Long artifactId, String strategy,
                                           Map<String, String> labelSelector, Long createdBy) {
        OtaArtifact artifact = getArtifact(artifactId);

        OtaDeployment deployment = OtaDeployment.builder()
                .organizationId(orgId)
                .artifactId(artifactId)
                .strategy(strategy != null ? strategy : "rolling")
                .labelSelector(labelSelector != null ? labelSelector : Map.of())
                .createdBy(createdBy)
                .build();
        deployment = deploymentRepository.save(deployment);

        // Find matching devices and create per-device status entries
        List<Device> allDevices = deviceRepository.findAll();
        List<Device> targetDevices = allDevices.stream()
                .filter(d -> d.getOrganizationId() != null && d.getOrganizationId().equals(orgId))
                .filter(d -> matchesLabels(d, labelSelector))
                .toList();

        for (Device device : targetDevices) {
            DeploymentDeviceStatus status = DeploymentDeviceStatus.builder()
                    .deploymentId(deployment.getId())
                    .deviceId(device.getDeviceId())
                    .build();
            deviceStatusRepository.save(status);

            // Publish OTA command via MQTT
            try {
                commandPublisher.publishCommand(device.getDeviceId(), "ota_update", Map.of(
                        "deployment_id", String.valueOf(deployment.getId()),
                        "artifact_name", artifact.getName(),
                        "artifact_version", artifact.getVersion(),
                        "sha256", artifact.getSha256(),
                        "ed25519_sig", artifact.getEd25519Sig() != null ? artifact.getEd25519Sig() : ""
                ));
            } catch (MqttException e) {
                log.error("Failed to publish OTA command to device {}: {}", device.getDeviceId(), e.getMessage());
            }
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

    @Transactional
    public void updateDeviceOtaStatus(Long deploymentId, String deviceId,
                                       String state, int progress, String errorMessage) {
        DeploymentDeviceStatus status = deviceStatusRepository
                .findByDeploymentIdAndDeviceId(deploymentId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Device status not found for deployment"));
        status.setState(state);
        status.setProgress(progress);
        if (errorMessage != null) status.setErrorMessage(errorMessage);
        if ("downloading".equals(state) && status.getStartedAt() == null) {
            status.setStartedAt(java.time.Instant.now());
        }
        if ("completed".equals(state) || "failed".equals(state) || "rolled_back".equals(state)) {
            status.setCompletedAt(java.time.Instant.now());
        }
        deviceStatusRepository.save(status);

        // Check if all devices are done
        checkDeploymentCompletion(deploymentId);
    }

    private void checkDeploymentCompletion(Long deploymentId) {
        List<DeploymentDeviceStatus> statuses = deviceStatusRepository.findByDeploymentId(deploymentId);
        boolean allDone = statuses.stream().allMatch(s ->
                "completed".equals(s.getState()) || "failed".equals(s.getState()) || "rolled_back".equals(s.getState()));
        if (allDone && !statuses.isEmpty()) {
            boolean anyFailed = statuses.stream().anyMatch(s ->
                    "failed".equals(s.getState()) || "rolled_back".equals(s.getState()));
            OtaDeployment deployment = deploymentRepository.findById(deploymentId).orElse(null);
            if (deployment != null) {
                deployment.setState(anyFailed ? "failed" : "completed");
                deploymentRepository.save(deployment);
            }
        }
    }

    private boolean matchesLabels(Device device, Map<String, String> selector) {
        if (selector == null || selector.isEmpty()) return true;
        Map<String, String> labels = device.getLabels();
        if (labels == null) return false;
        return selector.entrySet().stream()
                .allMatch(e -> e.getValue().equals(labels.get(e.getKey())));
    }
}
