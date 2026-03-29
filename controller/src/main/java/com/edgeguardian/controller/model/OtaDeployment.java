package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "ota_deployments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(nullable = false)
    @Builder.Default
    private String strategy = "rolling";

    @Column(nullable = false)
    @Builder.Default
    private DeploymentState state = DeploymentState.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_selector", nullable = false)
    @Builder.Default
    private Map<String, String> labelSelector = Map.of();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
