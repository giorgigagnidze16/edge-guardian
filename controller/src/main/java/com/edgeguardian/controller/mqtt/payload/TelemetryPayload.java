package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryPayload(String deviceId, Instant timestamp, DeviceStatusPayload status) {
}
