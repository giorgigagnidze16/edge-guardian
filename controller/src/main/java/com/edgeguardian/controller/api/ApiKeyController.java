package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.ApiKeyCreateResponse;
import com.edgeguardian.controller.dto.ApiKeyDto;
import com.edgeguardian.controller.dto.CreateApiKeyRequest;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.ApiKeyService;
import com.edgeguardian.controller.service.result.ApiKeyCreateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    public List<ApiKeyDto> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return apiKeyService.findByOrganization(principal.organizationId()).stream()
                .map(ApiKeyDto::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreateResponse create(@RequestBody CreateApiKeyRequest request,
                                       @AuthenticationPrincipal TenantPrincipal principal) {
        ApiKeyCreateResult result = apiKeyService.create(
                principal.organizationId(), request.name(), request.scopes(),
                request.expiresAt(), principal.userId());
        return new ApiKeyCreateResponse(ApiKeyDto.from(result.apiKey()), result.rawKey());
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long keyId,
                       @AuthenticationPrincipal TenantPrincipal principal) {
        apiKeyService.revoke(keyId, principal.organizationId());
    }
}
