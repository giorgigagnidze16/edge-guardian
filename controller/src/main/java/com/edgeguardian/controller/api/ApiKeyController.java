package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.ApiKeyService;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final OrganizationService organizationService;

    @GetMapping
    public List<ApiKeyDto> list(@PathVariable Long orgId,
                                @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        return apiKeyService.findByOrganization(orgId).stream()
                .map(ApiKeyDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreateResponse create(@PathVariable Long orgId,
                                       @RequestBody CreateApiKeyRequest request,
                                       @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(
                orgId, request.name(), request.scopes(), request.expiresAt(), principal.userId());
        return new ApiKeyCreateResponse(ApiKeyDto.from(result.apiKey()), result.rawKey());
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long orgId, @PathVariable Long keyId,
                       @AuthenticationPrincipal TenantPrincipal principal) {
        organizationService.requireRole(orgId, principal.userId(), OrgRole.owner, OrgRole.admin);
        apiKeyService.revoke(keyId);
    }
}
