package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "ota_artifacts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
