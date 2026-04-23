package com.edgeguardian.controller.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Runtime status reported by an agent via heartbeat.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceStatus {

    private double cpuUsagePercent;
    private long memoryUsedBytes;
    private long memoryTotalBytes;
    private long diskUsedBytes;
    private long diskTotalBytes;
    private long uptimeSeconds;
    private Instant lastReconcile;
    private String reconcileStatus;
}
