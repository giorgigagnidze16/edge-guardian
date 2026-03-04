package com.edgeguardian.controller.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Composite primary key for the device_telemetry hypertable.
 */
public class DeviceTelemetryId implements Serializable {

    private Instant time;
    private String deviceId;

    public DeviceTelemetryId() {}

    public DeviceTelemetryId(Instant time, String deviceId) {
        this.time = time;
        this.deviceId = deviceId;
    }

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceTelemetryId that)) return false;
        return Objects.equals(time, that.time) && Objects.equals(deviceId, that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, deviceId);
    }
}
