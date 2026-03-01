package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.EnrollmentToken;

import java.time.Instant;

public record EnrollmentTokenDto(
        Long id,
        String token,
        String name,
        Instant expiresAt,
        Integer maxUses,
        int useCount,
        boolean revoked,
        Instant createdAt
) {
    public static EnrollmentTokenDto from(EnrollmentToken t) {
        return new EnrollmentTokenDto(
                t.getId(),
                t.getToken(),
                t.getName(),
                t.getExpiresAt(),
                t.getMaxUses(),
                t.getUseCount(),
                t.isRevoked(),
                t.getCreatedAt()
        );
    }
}
