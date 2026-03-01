package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateEnrollmentTokenRequest;
import com.edgeguardian.controller.dto.EnrollmentTokenDto;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantContext;
import com.edgeguardian.controller.service.EnrollmentService;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/enrollment-tokens")
public class EnrollmentTokenController {

    private final EnrollmentService enrollmentService;
    private final OrganizationService organizationService;

    public EnrollmentTokenController(EnrollmentService enrollmentService,
                                     OrganizationService organizationService) {
        this.enrollmentService = enrollmentService;
        this.organizationService = organizationService;
    }

    @GetMapping
    public List<EnrollmentTokenDto> list(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId,
                OrgRole.owner, OrgRole.admin, OrgRole.operator);
        return enrollmentService.findByOrganization(orgId).stream()
                .map(EnrollmentTokenDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentTokenDto create(@PathVariable Long orgId,
                                     @RequestBody CreateEnrollmentTokenRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        return EnrollmentTokenDto.from(
                enrollmentService.createToken(orgId, request.name(), request.expiresAt(),
                        request.maxUses(), userId));
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long orgId, @PathVariable Long tokenId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        enrollmentService.revokeToken(tokenId);
    }

    private Long requireUserId() {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return userId;
    }
}
