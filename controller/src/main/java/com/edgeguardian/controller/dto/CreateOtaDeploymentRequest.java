package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.RolloutStrategy;

import java.util.Map;

/**
 * Request body for creating a new OTA deployment.
 *
 * @param artifactId    ID of the artifact to deploy.
 * @param strategy      Rollout strategy; defaults to {@code rolling} when omitted.
 * @param labelSelector Device label selector for targeting.
 */
public record CreateOtaDeploymentRequest(
        Long artifactId,
        RolloutStrategy strategy,
        Map<String, String> labelSelector
) {}
