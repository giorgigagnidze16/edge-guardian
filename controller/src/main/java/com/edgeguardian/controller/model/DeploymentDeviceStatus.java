package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "deployment_device_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeploymentDeviceStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(nullable = false)
    @Builder.Default
    private OtaDeviceState state = OtaDeviceState.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int progress = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
