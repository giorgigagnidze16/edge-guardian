package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CertificateDto;
import com.edgeguardian.controller.dto.CertificateRequestDto;
import com.edgeguardian.controller.dto.RejectCertRequest;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.mqtt.CertRequestListener;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.CertificateAuthorityService;
import com.edgeguardian.controller.service.CertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;
    private final CertificateAuthorityService caService;
    private final CertRequestListener certRequestListener;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    public List<CertificateDto> listCertificates(@AuthenticationPrincipal TenantPrincipal principal) {
        return certificateService.findCertificatesByOrganization(principal.organizationId()).stream()
                .map(CertificateDto::from)
                .toList();
    }

    @GetMapping("/requests")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    public List<CertificateRequestDto> listRequests(@AuthenticationPrincipal TenantPrincipal principal) {
        return certificateService.findRequestsByOrganization(principal.organizationId()).stream()
                .map(CertificateRequestDto::from)
                .toList();
    }

    @PostMapping("/requests/{requestId}/approve")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    public CertificateDto approve(
            @PathVariable Long requestId,
            @AuthenticationPrincipal TenantPrincipal principal) {
        IssuedCertificate cert = certificateService.approve(requestId, principal.organizationId(), principal.userId());
        certRequestListener.publishCertResponse(
                cert.getDeviceId(), principal.organizationId(), cert.getName(), cert.getCertPem());
        return CertificateDto.from(cert);
    }

    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(
            @PathVariable Long requestId,
            @RequestBody(required = false) RejectCertRequest body,
            @AuthenticationPrincipal TenantPrincipal principal) {
        certificateService.reject(requestId, principal.organizationId(), principal.userId(),
                body != null ? body.reason() : null);
    }

    @PostMapping("/{certId}/revoke")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long certId,
                       @AuthenticationPrincipal TenantPrincipal principal) {
        certificateService.revoke(certId, principal.organizationId(), principal.userId());
    }

    @GetMapping(value = "/ca", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public String getCaCert(@AuthenticationPrincipal TenantPrincipal principal) {
        return caService.getCaCertPem(principal.organizationId());
    }
}
