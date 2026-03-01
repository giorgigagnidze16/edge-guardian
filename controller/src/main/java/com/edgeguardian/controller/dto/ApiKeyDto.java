package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.ApiKey;

import java.time.Instant;
import java.util.List;

public record ApiKeyDto(
        Long id,
        String keyPrefix,
        String name,
        List<String> scopes,
        Instant expiresAt,
        boolean revoked,
        Instant lastUsedAt,
        Instant createdAt
) {
    public static ApiKeyDto from(ApiKey key) {
        return new ApiKeyDto(
                key.getId(),
                key.getKeyPrefix(),
                key.getName(),
                key.getScopes(),
                key.getExpiresAt(),
                key.isRevoked(),
                key.getLastUsedAt(),
                key.getCreatedAt()
        );
    }
}
