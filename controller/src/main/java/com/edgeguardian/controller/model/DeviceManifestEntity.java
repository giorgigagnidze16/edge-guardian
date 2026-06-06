package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * JPA entity representing a device manifest (desired state).
 */
@Entity
@Table(name = "device_manifests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceManifestEntity extends AbstractAuditedEntity {

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

    public DeviceManifestEntity(String deviceId, Map<String, Object> metadata, Map<String, Object> spec) {
        this.deviceId = deviceId;
        this.metadata = metadata;
        this.spec = spec;
        this.apiVersion = "edgeguardian/v1";
        this.kind = "DeviceManifest";
        this.version = 1;
    }
}
