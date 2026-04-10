package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateOtaDeploymentRequest;
import com.edgeguardian.controller.dto.OtaArtifactDto;
import com.edgeguardian.controller.dto.OtaDeploymentDto;
import com.edgeguardian.controller.model.DeploymentDeviceStatus;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.ArtifactStorageService;
import com.edgeguardian.controller.service.OTAService;
import com.edgeguardian.controller.service.OrganizationService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/ota")
@RequiredArgsConstructor
public class OTAController {

    private final OTAService otaService;
    private final OrganizationService organizationService;
    private final ArtifactStorageService artifactStorageService;

    // --- Artifacts ---

    @GetMapping("/artifacts")
    public List<OtaArtifactDto> listArtifacts(@PathVariable Long orgId,
                                               @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.listArtifacts(orgId).stream()
            .map(OtaArtifactDto::from)
            .toList();
    }

    @PostMapping("/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaArtifactDto createArtifact(
        @PathVariable Long orgId,
        @RequestPart("file") MultipartFile file,
        @RequestPart("name") String name,
        @RequestPart("version") String version,
        @RequestPart("architecture") String architecture,
        @RequestPart(value = "ed25519Sig", required = false) String ed25519Sig,
        @AuthenticationPrincipal TenantPrincipal principal) throws IOException {

        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator);

        var result = artifactStorageService.store(orgId, name, version, architecture, file.getInputStream());

        return OtaArtifactDto.from(otaService.createArtifact(
            orgId, name, version, architecture,
            result.size(), result.sha256(), ed25519Sig, result.storagePath(), principal.userId()));
    }

    @DeleteMapping("/artifacts/{artifactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArtifact(@PathVariable Long orgId, @PathVariable Long artifactId,
                               @AuthenticationPrincipal TenantPrincipal principal) throws IOException {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        var artifact = otaService.getArtifact(artifactId);
        if (artifact.getS3Key() != null) {
            artifactStorageService.delete(artifact.getS3Key());
        }
        otaService.deleteArtifact(artifactId);
    }

    // --- Deployments ---

    @GetMapping("/deployments")
    public List<OtaDeploymentDto> listDeployments(@PathVariable Long orgId,
                                                   @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.listDeployments(orgId).stream()
            .map(OtaDeploymentDto::from)
            .toList();
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaDeploymentDto createDeployment(@PathVariable Long orgId,
                                             @RequestBody CreateOtaDeploymentRequest request,
                                             @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator);
        return OtaDeploymentDto.from(otaService.createDeployment(
            orgId, request.artifactId(), request.strategy(),
            request.labelSelector(), principal.userId()));
    }

    @GetMapping("/deployments/{deploymentId}")
    public OtaDeploymentDto getDeployment(@PathVariable Long orgId, @PathVariable Long deploymentId,
                                          @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return OtaDeploymentDto.from(otaService.getDeployment(deploymentId));
    }

    @GetMapping("/deployments/{deploymentId}/devices")
    public List<DeploymentDeviceStatus> getDeploymentDevices(@PathVariable Long orgId,
                                                             @PathVariable Long deploymentId,
                                                             @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(),
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.getDeploymentDeviceStatuses(deploymentId);
    }
}