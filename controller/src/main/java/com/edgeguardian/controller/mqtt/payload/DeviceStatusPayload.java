package com.edgeguardian.controller.mqtt.payload;

import com.edgeguardian.controller.model.DeviceStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceStatusPayload(
        double cpuUsagePercent,
        long memoryUsedBytes,
        long memoryTotalBytes,
        long diskUsedBytes,
        long diskTotalBytes,
        long uptimeSeconds,
        String lastReconcile,
        String reconcileStatus
) {
    public DeviceStatus toEntity() {
        var s = new DeviceStatus();
        s.setCpuUsagePercent(cpuUsagePercent);
        s.setMemoryUsedBytes(memoryUsedBytes);
        s.setMemoryTotalBytes(memoryTotalBytes);
        s.setDiskUsedBytes(diskUsedBytes);
        s.setDiskTotalBytes(diskTotalBytes);
        s.setUptimeSeconds(uptimeSeconds);
        if (lastReconcile != null && !lastReconcile.isEmpty()) {
            try {
                s.setLastReconcile(Instant.parse(lastReconcile));
            } catch (Exception ignored) {
            }
        }
        s.setReconcileStatus(reconcileStatus != null ? reconcileStatus : "unknown");
        return s;
    }
}
