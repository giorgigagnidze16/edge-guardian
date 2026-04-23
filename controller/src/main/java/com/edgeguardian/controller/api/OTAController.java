package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateOtaDeploymentRequest;
import com.edgeguardian.controller.dto.OtaArtifactDto;
import com.edgeguardian.controller.dto.OtaDeploymentDto;
import com.edgeguardian.controller.model.DeploymentDeviceStatus;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.ArtifactStorageService;
import com.edgeguardian.controller.service.OTAService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping(ApiPaths.OTA_BASE)
@RequiredArgsConstructor
public class OTAController {

    private final OTAService otaService;
    private final ArtifactStorageService artifactStorageService;

    @GetMapping("/artifacts")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<OtaArtifactDto> listArtifacts(@AuthenticationPrincipal TenantPrincipal principal) {
        return otaService.listArtifacts(principal.organizationId()).stream()
                .map(OtaArtifactDto::from)
                .toList();
    }

    @PostMapping("/artifacts")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaArtifactDto createArtifact(
            @RequestPart("file") MultipartFile file,
            @RequestPart("name") String name,
            @RequestPart("version") String version,
            @RequestPart("architecture") String architecture,
            @RequestPart(value = "ed25519Sig", required = false) String ed25519Sig,
            @AuthenticationPrincipal TenantPrincipal principal) throws IOException {
        Long orgId = principal.organizationId();
        var result = artifactStorageService.store(orgId, name, version, architecture, file.getInputStream());
        return OtaArtifactDto.from(otaService.createArtifact(
                orgId, name, version, architecture,
                result.size(), result.sha256(), ed25519Sig, result.storagePath(), principal.userId()));
    }

    @DeleteMapping("/artifacts/{artifactId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArtifact(@PathVariable Long artifactId,
                               @AuthenticationPrincipal TenantPrincipal principal) throws IOException {
        var artifact = otaService.getArtifact(artifactId, principal.organizationId());
        if (artifact.getS3Key() != null) {
            artifactStorageService.delete(artifact.getS3Key());
        }
        otaService.deleteArtifact(artifactId, principal.organizationId());
    }

    @GetMapping("/deployments")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<OtaDeploymentDto> listDeployments(@AuthenticationPrincipal TenantPrincipal principal) {
        return otaService.listDeployments(principal.organizationId()).stream()
                .map(OtaDeploymentDto::from)
                .toList();
    }

    @PostMapping("/deployments")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaDeploymentDto createDeployment(@RequestBody CreateOtaDeploymentRequest request,
                                             @AuthenticationPrincipal TenantPrincipal principal) {
        return OtaDeploymentDto.from(otaService.createDeployment(
                principal.organizationId(), request.artifactId(), request.strategy(),
                request.labelSelector(), principal.userId()));
    }

    @GetMapping("/deployments/{deploymentId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public OtaDeploymentDto getDeployment(@PathVariable Long deploymentId,
                                          @AuthenticationPrincipal TenantPrincipal principal) {
        return OtaDeploymentDto.from(otaService.getDeployment(deploymentId, principal.organizationId()));
    }

    @GetMapping("/deployments/{deploymentId}/devices")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<DeploymentDeviceStatus> getDeploymentDevices(@PathVariable Long deploymentId,
                                                             @AuthenticationPrincipal TenantPrincipal principal) {
        return otaService.getDeploymentDeviceStatuses(deploymentId, principal.organizationId());
    }
}
