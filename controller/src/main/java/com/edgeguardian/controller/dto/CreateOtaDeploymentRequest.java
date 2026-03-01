package com.edgeguardian.controller.dto;

import java.util.Map;

public record CreateOtaDeploymentRequest(
        Long artifactId,
        String strategy,
        Map<String, String> labelSelector
) {}
