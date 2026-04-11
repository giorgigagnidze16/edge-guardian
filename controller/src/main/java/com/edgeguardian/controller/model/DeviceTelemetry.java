package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only telemetry entity backed by the device_telemetry TimescaleDB hypertable.
 */
@Entity
@Table(name = "device_telemetry")
@IdClass(DeviceTelemetryId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceTelemetry {

    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "cpu_usage_percent")
    private double cpuUsagePercent;

    @Column(name = "memory_used_bytes")
    private long memoryUsedBytes;

    @Column(name = "memory_total_bytes")
    private long memoryTotalBytes;

    @Column(name = "disk_used_bytes")
    private long diskUsedBytes;

    @Column(name = "disk_total_bytes")
    private long diskTotalBytes;

    @Column(name = "temperature_celsius")
    private double temperatureCelsius;

    @Column(name = "uptime_seconds")
    private long uptimeSeconds;

    @Column(name = "last_reconcile")
    private Instant lastReconcile;

    @Column(name = "reconcile_status")
    private String reconcileStatus;

    /**
     * Factory: create a telemetry row from a heartbeat status report.
     */
    public static DeviceTelemetry from(String deviceId, Long organizationId, DeviceStatus status) {
        return DeviceTelemetry.builder()
                .time(Instant.now())
                .deviceId(deviceId)
                .organizationId(organizationId)
                .cpuUsagePercent(status.getCpuUsagePercent())
                .memoryUsedBytes(status.getMemoryUsedBytes())
                .memoryTotalBytes(status.getMemoryTotalBytes())
                .diskUsedBytes(status.getDiskUsedBytes())
                .diskTotalBytes(status.getDiskTotalBytes())
                .temperatureCelsius(status.getTemperatureCelsius())
                .uptimeSeconds(status.getUptimeSeconds())
                .lastReconcile(status.getLastReconcile())
                .reconcileStatus(status.getReconcileStatus())
                .build();
    }

    /**
     * Convert this telemetry row back to a DeviceStatus DTO.
     */
    public DeviceStatus toDeviceStatus() {
        return DeviceStatus.builder()
                .cpuUsagePercent(this.cpuUsagePercent)
                .memoryUsedBytes(this.memoryUsedBytes)
                .memoryTotalBytes(this.memoryTotalBytes)
                .diskUsedBytes(this.diskUsedBytes)
                .diskTotalBytes(this.diskTotalBytes)
                .temperatureCelsius(this.temperatureCelsius)
                .uptimeSeconds(this.uptimeSeconds)
                .lastReconcile(this.lastReconcile)
                .reconcileStatus(this.reconcileStatus)
                .build();
    }
}
