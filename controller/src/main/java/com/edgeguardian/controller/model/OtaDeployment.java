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

@Entity
@Table(name = "ota_deployments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaDeployment extends AbstractAuditedEntity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(nullable = false)
    @Builder.Default
    private RolloutStrategy strategy = RolloutStrategy.ROLLING;

    @Column(nullable = false)
    @Builder.Default
    private DeploymentState state = DeploymentState.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_selector", nullable = false)
    @Builder.Default
    private Map<String, String> labelSelector = Map.of();

    @Column(name = "created_by")
    private Long createdBy;
}
