package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.DeploymentDeviceStatus;
import com.edgeguardian.controller.model.DeploymentState;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.OtaArtifact;
import com.edgeguardian.controller.model.OtaDeployment;
import com.edgeguardian.controller.model.OtaDeviceState;
import com.edgeguardian.controller.mqtt.CommandPublisher;
import com.edgeguardian.controller.repository.DeploymentDeviceStatusRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.OtaArtifactRepository;
import com.edgeguardian.controller.repository.OtaDeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.edgeguardian.controller.model.OtaDeviceState.DOWNLOADING;
import static com.edgeguardian.controller.model.OtaDeviceState.FAILED;
import static com.edgeguardian.controller.model.OtaDeviceState.PENDING;
import static com.edgeguardian.controller.model.OtaDeviceState.ROLLED_BACK;

@Slf4j
@Service
@RequiredArgsConstructor
public class OTAService {

    private final OtaArtifactRepository artifactRepository;
    private final OtaDeploymentRepository deploymentRepository;
    private final DeploymentDeviceStatusRepository deviceStatusRepository;
    private final DeviceRepository deviceRepository;
    private final CommandPublisher commandPublisher;
    private final ArtifactStorageService artifactStorageService;

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
    public OtaArtifact getArtifact(Long artifactId, Long expectedOrgId) {
        return artifactRepository.findById(artifactId)
                .filter(a -> expectedOrgId.equals(a.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found"));
    }

    @Transactional
    public void deleteArtifact(Long artifactId, Long expectedOrgId) {
        getArtifact(artifactId, expectedOrgId);
        artifactRepository.deleteById(artifactId);
    }

    // --- Deployments ---

    // NOTE: The `strategy` parameter (rolling/canary/immediate) is descriptive-only and
    // NOT YET IMPLEMENTED. This method always performs an immediate fan-out - every matching
    // device receives the OTA command in the same transaction. The value is stored on the
    // deployment row so a future staged-rollout implementation can hook in without API changes.
    @Transactional
    public OtaDeployment createDeployment(Long orgId, Long artifactId, String strategy,
                                          Map<String, String> labelSelector, Long createdBy) {
        var artifact = getArtifact(artifactId, orgId);

        var deployment = deploymentRepository.save(OtaDeployment.builder()
                .organizationId(orgId)
                .artifactId(artifactId)
                .strategy(strategy != null ? strategy : "rolling")
                .labelSelector(labelSelector != null ? labelSelector : Map.of())
                .createdBy(createdBy)
                .build());

        // Org-scoped lookup at the repository layer - was previously findAll() + in-memory
        // filter which (a) leaks bytes proportional to total fleet size and (b) risked
        // cross-tenant deployment if the filter clause ever got dropped in a refactor.
        var targetDevices = deviceRepository.findByOrganizationId(orgId).stream()
                .filter(d -> matchesLabels(d, labelSelector))
                .toList();

        var downloadUrl = artifactStorageService.generatePresignedUrl(
                artifact.getS3Key(), java.time.Duration.ofHours(1));

        for (Device device : targetDevices) {
            deviceStatusRepository.save(DeploymentDeviceStatus.builder()
                    .deploymentId(deployment.getId())
                    .deviceId(device.getDeviceId())
                    .build());

            publishOtaCommand(device.getDeviceId(), deployment.getId(), artifact, downloadUrl);
        }

        if (!targetDevices.isEmpty()) {
            deployment.setState(DeploymentState.IN_PROGRESS);
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
    public OtaDeployment getDeployment(Long deploymentId, Long expectedOrgId) {
        return deploymentRepository.findById(deploymentId)
                .filter(d -> expectedOrgId.equals(d.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment not found"));
    }

    @Transactional(readOnly = true)
    public List<DeploymentDeviceStatus> getDeploymentDeviceStatuses(Long deploymentId, Long expectedOrgId) {
        getDeployment(deploymentId, expectedOrgId);
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

        var otaState = OtaDeviceState.fromDbValue(state);
        status.setState(otaState);
        status.setProgress(progress);
        if (errorMessage != null) status.setErrorMessage(errorMessage);
        if (otaState == DOWNLOADING && status.getStartedAt() == null) {
            status.setStartedAt(Instant.now());
        }
        if (otaState.isTerminal()) {
            status.setCompletedAt(Instant.now());
        }
        deviceStatusRepository.save(status);

        checkDeploymentCompletion(deploymentId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingOtaCommands(String deviceId) {
        var pendingStatuses = deviceStatusRepository.findByDeviceIdAndState(deviceId, PENDING);
        List<Map<String, Object>> commands = new ArrayList<>();

        for (var status : pendingStatuses) {
            var artifact = artifactRepository.findById(
                    deploymentRepository.findById(status.getDeploymentId())
                            .map(OtaDeployment::getArtifactId).orElse(-1L));
            if (artifact.isEmpty()) continue;

            var a = artifact.get();
            var presignedUrl = artifactStorageService.generatePresignedUrl(
                    a.getS3Key(), java.time.Duration.ofHours(1));

            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("id", UUID.randomUUID().toString());
            cmd.put("type", "ota_update");
            cmd.put("params", Map.of(
                    "deployment_id", String.valueOf(status.getDeploymentId()),
                    "artifact_name", a.getName(),
                    "artifact_version", a.getVersion(),
                    "sha256", a.getSha256(),
                    "ed25519_sig", a.getEd25519Sig() != null ? a.getEd25519Sig() : "",
                    "download_url", presignedUrl
            ));
            cmd.put("createdAt", Instant.now().toString());
            commands.add(cmd);
        }

        return commands;
    }

    // --- Private helpers ---

    private void checkDeploymentCompletion(Long deploymentId) {
        var statuses = deviceStatusRepository.findByDeploymentId(deploymentId);

        boolean allDone = statuses.stream().allMatch(s -> s.getState().isTerminal());
        if (allDone && !statuses.isEmpty()) {
            boolean anyFailed = statuses.stream()
                    .anyMatch(s -> s.getState() == FAILED || s.getState() == ROLLED_BACK);
            deploymentRepository.findById(deploymentId).ifPresent(deployment -> {
                deployment.setState(anyFailed ? DeploymentState.FAILED : DeploymentState.COMPLETED);
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