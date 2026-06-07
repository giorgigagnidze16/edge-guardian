package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity representing a registered edge device.
 */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device extends AbstractAuditedEntity {

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
    @Builder.Default
    private DeviceState state = DeviceState.ONLINE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "labels")
    @Builder.Default
    private Map<String, String> labels = new HashMap<>();

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "auto_update")
    private Boolean autoUpdate;

    public Device(String deviceId, String hostname, String architecture, String os, String agentVersion) {
        this.deviceId = deviceId;
        this.hostname = hostname;
        this.architecture = architecture;
        this.os = os;
        this.agentVersion = agentVersion;
        this.labels = new HashMap<>();
        this.state = DeviceState.ONLINE;
        this.lastHeartbeat = Instant.now();
    }

    @PrePersist
    void stampRegisteredAt() {
        if (registeredAt == null) {
            registeredAt = Instant.now();
        }
    }

    public enum DeviceState {
        ONLINE, DEGRADED, OFFLINE, SUSPENDED
    }
}
