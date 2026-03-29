package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Tracks commands sent to devices with their execution lifecycle.
 */
@Entity
@Table(name = "device_commands")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, unique = true)
    private String commandId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private Map<String, String> params = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> script;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> hooks;

    @Column(name = "timeout_seconds", nullable = false)
    @Builder.Default
    private int timeoutSeconds = 0;

    @Column(nullable = false)
    @Builder.Default
    private String state = "pending";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
