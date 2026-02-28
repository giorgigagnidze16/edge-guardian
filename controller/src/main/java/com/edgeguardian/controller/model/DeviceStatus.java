package com.edgeguardian.controller.model;

import java.time.Instant;

/**
 * Runtime status reported by an agent via heartbeat.
 */
public class DeviceStatus {

    private double cpuUsagePercent;
    private long memoryUsedBytes;
    private long memoryTotalBytes;
    private long diskUsedBytes;
    private long diskTotalBytes;
    private double temperatureCelsius;
    private long uptimeSeconds;
    private Instant lastReconcile;
    private String reconcileStatus;

    public DeviceStatus() {}

    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }

    public long getMemoryUsedBytes() { return memoryUsedBytes; }
    public void setMemoryUsedBytes(long memoryUsedBytes) { this.memoryUsedBytes = memoryUsedBytes; }

    public long getMemoryTotalBytes() { return memoryTotalBytes; }
    public void setMemoryTotalBytes(long memoryTotalBytes) { this.memoryTotalBytes = memoryTotalBytes; }

    public long getDiskUsedBytes() { return diskUsedBytes; }
    public void setDiskUsedBytes(long diskUsedBytes) { this.diskUsedBytes = diskUsedBytes; }

    public long getDiskTotalBytes() { return diskTotalBytes; }
    public void setDiskTotalBytes(long diskTotalBytes) { this.diskTotalBytes = diskTotalBytes; }

    public double getTemperatureCelsius() { return temperatureCelsius; }
    public void setTemperatureCelsius(double temperatureCelsius) { this.temperatureCelsius = temperatureCelsius; }

    public long getUptimeSeconds() { return uptimeSeconds; }
    public void setUptimeSeconds(long uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }

    public Instant getLastReconcile() { return lastReconcile; }
    public void setLastReconcile(Instant lastReconcile) { this.lastReconcile = lastReconcile; }

    public String getReconcileStatus() { return reconcileStatus; }
    public void setReconcileStatus(String reconcileStatus) { this.reconcileStatus = reconcileStatus; }
}
