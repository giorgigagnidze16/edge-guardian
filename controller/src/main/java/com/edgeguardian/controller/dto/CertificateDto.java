package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.IssuedCertificate;

import java.time.Instant;

public record CertificateDto(
        Long id,
        String deviceId,
        String name,
        String commonName,
        String serialNumber,
        String status,
        Instant notBefore,
        Instant notAfter,
        boolean revoked,
        String revokeReason,
        Instant createdAt
) {
    public static CertificateDto from(IssuedCertificate c) {
        return new CertificateDto(
                c.getId(),
                c.getDeviceId(),
                c.getName(),
                c.getCommonName(),
                c.getSerialNumber(),
                computeStatus(c),
                c.getNotBefore(),
                c.getNotAfter(),
                c.isRevoked(),
                c.getRevokeReason() != null ? c.getRevokeReason().name().toLowerCase() : null,
                c.getCreatedAt()
        );
    }

    private static String computeStatus(IssuedCertificate c) {
        if (c.isRevoked()) return "revoked";
        Instant now = Instant.now();
        if (now.isAfter(c.getNotAfter())) return "expired";
        if (now.isAfter(c.getNotAfter().minusSeconds(30L * 24 * 3600))) return "expiring";
        return "valid";
    }
}
