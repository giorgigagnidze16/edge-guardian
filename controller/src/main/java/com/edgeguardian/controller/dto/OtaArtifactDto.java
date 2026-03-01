package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.OtaArtifact;

import java.time.Instant;

public record OtaArtifactDto(
        Long id,
        String name,
        String version,
        String architecture,
        long size,
        String sha256,
        Instant createdAt
) {
    public static OtaArtifactDto from(OtaArtifact a) {
        return new OtaArtifactDto(
                a.getId(), a.getName(), a.getVersion(), a.getArchitecture(),
                a.getSize(), a.getSha256(), a.getCreatedAt());
    }
}
