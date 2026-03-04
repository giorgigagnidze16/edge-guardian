package com.edgeguardian.controller.dto;

public record AgentOtaStatusRequest(
        long deploymentId,
        String deviceId,
        String state,
        int progress,
        String errorMessage
) {}
