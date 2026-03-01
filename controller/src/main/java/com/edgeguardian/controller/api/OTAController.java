package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.DeploymentDeviceStatus;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantContext;
import com.edgeguardian.controller.service.OTAService;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/ota")
public class OTAController {

    private final OTAService otaService;
    private final OrganizationService organizationService;

    public OTAController(OTAService otaService, OrganizationService organizationService) {
        this.otaService = otaService;
        this.organizationService = organizationService;
    }

    // --- Artifacts ---

    @GetMapping("/artifacts")
    public List<OtaArtifactDto> listArtifacts(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.listArtifacts(orgId).stream()
                .map(OtaArtifactDto::from)
                .toList();
    }

    @PostMapping("/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaArtifactDto createArtifact(@PathVariable Long orgId,
                                         @RequestBody CreateOtaArtifactRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin, OrgRole.operator);
        return OtaArtifactDto.from(otaService.createArtifact(
                orgId, request.name(), request.version(), request.architecture(),
                request.size(), request.sha256(), request.ed25519Sig(), request.s3Key(), userId));
    }

    // --- Deployments ---

    @GetMapping("/deployments")
    public List<OtaDeploymentDto> listDeployments(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.listDeployments(orgId).stream()
                .map(OtaDeploymentDto::from)
                .toList();
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public OtaDeploymentDto createDeployment(@PathVariable Long orgId,
                                             @RequestBody CreateOtaDeploymentRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin, OrgRole.operator);
        return OtaDeploymentDto.from(otaService.createDeployment(
                orgId, request.artifactId(), request.strategy(),
                request.labelSelector(), userId));
    }

    @GetMapping("/deployments/{deploymentId}")
    public OtaDeploymentDto getDeployment(@PathVariable Long orgId, @PathVariable Long deploymentId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return OtaDeploymentDto.from(otaService.getDeployment(deploymentId));
    }

    @GetMapping("/deployments/{deploymentId}/devices")
    public List<DeploymentDeviceStatus> getDeploymentDevices(@PathVariable Long orgId,
                                                              @PathVariable Long deploymentId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator, OrgRole.viewer);
        return otaService.getDeploymentDeviceStatuses(deploymentId);
    }

    private Long requireUserId() {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return userId;
    }
}
