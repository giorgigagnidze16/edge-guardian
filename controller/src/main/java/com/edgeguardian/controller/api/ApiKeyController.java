package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.*;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantContext;
import com.edgeguardian.controller.service.ApiKeyService;
import com.edgeguardian.controller.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final OrganizationService organizationService;

    public ApiKeyController(ApiKeyService apiKeyService,
                            OrganizationService organizationService) {
        this.apiKeyService = apiKeyService;
        this.organizationService = organizationService;
    }

    @GetMapping
    public List<ApiKeyDto> list(@PathVariable Long orgId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        return apiKeyService.findByOrganization(orgId).stream()
                .map(ApiKeyDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreateResponse create(@PathVariable Long orgId,
                                       @RequestBody CreateApiKeyRequest request) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(
                orgId, request.name(), request.scopes(), request.expiresAt(), userId);
        return new ApiKeyCreateResponse(ApiKeyDto.from(result.apiKey()), result.rawKey());
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long orgId, @PathVariable Long keyId) {
        Long userId = requireUserId();
        organizationService.requireRole(orgId, userId, OrgRole.owner, OrgRole.admin);
        apiKeyService.revoke(keyId);
    }

    private Long requireUserId() {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return userId;
    }
}
