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

import java.time.Instant;
import java.util.Map;

/**
 * Tracks commands sent to devices with their execution lifecycle.
 */
@Entity
@Table(name = "device_commands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand extends AbstractCreatedEntity {

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

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
