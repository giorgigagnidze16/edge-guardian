package com.edgeguardian.controller.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Composite primary key for the device_telemetry hypertable.
 */
public record DeviceTelemetryId(Instant time, String deviceId) implements Serializable {
    public DeviceTelemetryId() { this(null, null); }
}
