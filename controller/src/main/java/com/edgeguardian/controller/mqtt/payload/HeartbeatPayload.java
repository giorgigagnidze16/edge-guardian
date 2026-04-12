package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatPayload(
        String deviceId,
        String agentVersion,
        DeviceStatusPayload status,
        long manifestVersion,
        Instant timestamp
) {}
