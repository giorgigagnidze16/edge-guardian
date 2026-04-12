package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateEnrollmentTokenRequest;
import com.edgeguardian.controller.dto.EnrollmentTokenDto;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enrollment-tokens")
@RequiredArgsConstructor
public class EnrollmentTokenController {

    private final EnrollmentService enrollmentService;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    public List<EnrollmentTokenDto> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return enrollmentService.findByOrganization(principal.organizationId()).stream()
                .map(EnrollmentTokenDto::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentTokenDto create(
            @RequestBody CreateEnrollmentTokenRequest request,
            @AuthenticationPrincipal TenantPrincipal principal) {
        return EnrollmentTokenDto.from(
                enrollmentService.createToken(principal.organizationId(), request.resolvedName(),
                        request.expiresAt(), request.maxUses(), principal.userId()));
    }

    @DeleteMapping("/{tokenId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long tokenId,
                       @AuthenticationPrincipal TenantPrincipal principal) {
        enrollmentService.revokeToken(tokenId, principal.organizationId());
    }
}
