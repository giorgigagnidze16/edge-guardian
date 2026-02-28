package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * JPA entity representing a registered edge device.
 * Backed by the 'devices' table in PostgreSQL.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "hostname")
    private String hostname;

    @Column(name = "architecture")
    private String architecture;

    @Column(name = "os")
    private String os;

    @Column(name = "agent_version")
    private String agentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private DeviceState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "labels", columnDefinition = "jsonb")
    private Map<String, String> labels;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    // Runtime status fields (updated on heartbeat).
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Device() {
        this.labels = new HashMap<>();
        this.state = DeviceState.ONLINE;
        this.reconcileStatus = "converged";
    }

    public Device(String deviceId, String hostname, String architecture, String os, String agentVersion) {
        this();
        this.deviceId = deviceId;
        this.hostname = hostname;
        this.architecture = architecture;
        this.os = os;
        this.agentVersion = agentVersion;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.registeredAt == null) {
            this.registeredAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Updates runtime status fields from a DeviceStatus DTO.
     */
    public void updateStatus(DeviceStatus status) {
        if (status == null) return;
        this.cpuUsagePercent = status.getCpuUsagePercent();
        this.memoryUsedBytes = status.getMemoryUsedBytes();
        this.memoryTotalBytes = status.getMemoryTotalBytes();
        this.diskUsedBytes = status.getDiskUsedBytes();
        this.diskTotalBytes = status.getDiskTotalBytes();
        this.temperatureCelsius = status.getTemperatureCelsius();
        this.uptimeSeconds = status.getUptimeSeconds();
        if (status.getLastReconcile() != null) {
            this.lastReconcile = status.getLastReconcile();
        }
        if (status.getReconcileStatus() != null) {
            this.reconcileStatus = status.getReconcileStatus();
        }
    }

    /**
     * Returns the device's current runtime status as a DTO.
     * Alias for toDeviceStatus(), used by DeviceDto.from().
     */
    public DeviceStatus getStatus() {
        return toDeviceStatus();
    }

    /**
     * Converts current status fields to a DeviceStatus DTO.
     */
    public DeviceStatus toDeviceStatus() {
        DeviceStatus s = new DeviceStatus();
        s.setCpuUsagePercent(this.cpuUsagePercent);
        s.setMemoryUsedBytes(this.memoryUsedBytes);
        s.setMemoryTotalBytes(this.memoryTotalBytes);
        s.setDiskUsedBytes(this.diskUsedBytes);
        s.setDiskTotalBytes(this.diskTotalBytes);
        s.setTemperatureCelsius(this.temperatureCelsius);
        s.setUptimeSeconds(this.uptimeSeconds);
        s.setLastReconcile(this.lastReconcile);
        s.setReconcileStatus(this.reconcileStatus);
        return s;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public DeviceState getState() { return state; }
    public void setState(DeviceState state) { this.state = state; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public long getMemoryUsedBytes() { return memoryUsedBytes; }
    public long getMemoryTotalBytes() { return memoryTotalBytes; }
    public long getDiskUsedBytes() { return diskUsedBytes; }
    public long getDiskTotalBytes() { return diskTotalBytes; }
    public double getTemperatureCelsius() { return temperatureCelsius; }
    public long getUptimeSeconds() { return uptimeSeconds; }
    public Instant getLastReconcile() { return lastReconcile; }
    public String getReconcileStatus() { return reconcileStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public enum DeviceState {
        ONLINE, DEGRADED, OFFLINE
    }
}
