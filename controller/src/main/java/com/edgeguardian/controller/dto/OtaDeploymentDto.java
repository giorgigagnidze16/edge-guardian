package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.OtaDeployment;

import java.time.Instant;
import java.util.Map;

public record OtaDeploymentDto(
        Long id,
        Long artifactId,
        String strategy,
        String state,
        Map<String, String> labelSelector,
        Instant createdAt
) {
    public static OtaDeploymentDto from(OtaDeployment d) {
        return new OtaDeploymentDto(
                d.getId(), d.getArtifactId(), d.getStrategy(),
                d.getState(), d.getLabelSelector(), d.getCreatedAt());
    }
}
