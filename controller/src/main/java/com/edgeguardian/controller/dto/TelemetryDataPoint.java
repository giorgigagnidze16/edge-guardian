package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.DeviceTelemetry;

import java.time.Instant;

/**
 * DTO for time-series telemetry API responses.
 */
public record TelemetryDataPoint(
        Instant time,
        double cpuUsagePercent,
        long memoryUsedBytes,
        long memoryTotalBytes,
        long diskUsedBytes,
        long diskTotalBytes,
        double temperatureCelsius,
        long uptimeSeconds,
        String reconcileStatus
) {
    public static TelemetryDataPoint from(DeviceTelemetry t) {
        return new TelemetryDataPoint(
                t.getTime(),
                t.getCpuUsagePercent(),
                t.getMemoryUsedBytes(),
                t.getMemoryTotalBytes(),
                t.getDiskUsedBytes(),
                t.getDiskTotalBytes(),
                t.getTemperatureCelsius(),
                t.getUptimeSeconds(),
                t.getReconcileStatus()
        );
    }
}
