package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandResultPayload(
        String commandId,
        String deviceId,
        String phase,
        String status,
        int exitCode,
        String stdout,
        String stderr,
        String errorMessage,
        long durationMs,
        Instant timestamp
) {}
