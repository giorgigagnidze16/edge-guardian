package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * JPA entity representing a device manifest (desired state).
 * Backed by the 'device_manifests' table in PostgreSQL.
 * The spec is stored as JSONB for flexibility — the agent interprets the structure.
 */
@Entity
@Table(name = "device_manifests")
public class DeviceManifestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "api_version", nullable = false)
    private String apiVersion = "edgeguardian/v1";

    @Column(name = "kind", nullable = false)
    private String kind = "DeviceManifest";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec")
    private Map<String, Object> spec;

    @Column(name = "version", nullable = false)
    private long version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DeviceManifestEntity() {}

    public DeviceManifestEntity(String deviceId, Map<String, Object> metadata, Map<String, Object> spec) {
        this.deviceId = deviceId;
        this.metadata = metadata;
        this.spec = spec;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Map<String, Object> getSpec() { return spec; }
    public void setSpec(Map<String, Object> spec) { this.spec = spec; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
