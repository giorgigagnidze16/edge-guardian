package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ota_artifacts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaArtifact extends AbstractCreatedEntity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String architecture;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "ed25519_sig")
    private String ed25519Sig;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "created_by")
    private Long createdBy;
}
