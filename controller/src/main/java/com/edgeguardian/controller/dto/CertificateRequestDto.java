package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.CertificateRequest;

import java.time.Instant;
import java.util.List;

public record CertificateRequestDto(
        Long id,
        String deviceId,
        String name,
        String commonName,
        List<String> sans,
        String type,
        String state,
        String rejectReason,
        Instant reviewedAt,
        Instant createdAt
) {
    public static CertificateRequestDto from(CertificateRequest r) {
        return new CertificateRequestDto(
                r.getId(),
                r.getDeviceId(),
                r.getName(),
                r.getCommonName(),
                r.getSans(),
                r.getType().name().toLowerCase(),
                r.getState().name().toLowerCase(),
                r.getRejectReason(),
                r.getReviewedAt(),
                r.getCreatedAt()
        );
    }
}
