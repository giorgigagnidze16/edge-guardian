package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * JPA entity representing a device manifest (desired state).
 * Backed by the 'device_manifests' table in PostgreSQL.
 */
@Entity
@Table(name = "device_manifests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceManifestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "api_version", nullable = false)
    @Builder.Default
    private String apiVersion = "edgeguardian/v1";

    @Column(name = "kind", nullable = false)
    @Builder.Default
    private String kind = "DeviceManifest";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec")
    private Map<String, Object> spec;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private long version = 1;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DeviceManifestEntity(String deviceId, Map<String, Object> metadata, Map<String, Object> spec) {
        this.deviceId = deviceId;
        this.metadata = metadata;
        this.spec = spec;
        this.apiVersion = "edgeguardian/v1";
        this.kind = "DeviceManifest";
        this.version = 1;
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
}
