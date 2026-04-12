package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OtaStatusPayload(
        long deploymentId,
        String deviceId,
        String state,
        int progress,
        String errorMessage
) {}
