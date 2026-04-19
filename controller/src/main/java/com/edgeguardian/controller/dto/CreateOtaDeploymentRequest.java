package com.edgeguardian.controller.dto;

import java.util.Map;

/**
 * Request body for creating a new OTA deployment.
 *
 * @param artifactId     ID of the artifact to deploy.
 * @param strategy       Rollout strategy hint (rolling/canary/immediate). <b>NOT YET
 *                       IMPLEMENTED</b> - the controller currently fans out to all
 *                       matching devices immediately regardless of this value. The
 *                       field is accepted and persisted for forward compatibility.
 * @param labelSelector  Device label selector for targeting.
 */
public record CreateOtaDeploymentRequest(
        Long artifactId,
        String strategy,
        Map<String, String> labelSelector
) {}
