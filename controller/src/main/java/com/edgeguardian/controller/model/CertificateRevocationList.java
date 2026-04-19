package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "certificate_revocation_lists")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateRevocationList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Builder.Default
    @Column(name = "crl_number", nullable = false)
    private Long crlNumber = 1L;

    @Column(name = "crl_der", nullable = false)
    private byte[] crlDer;

    @Column(name = "this_update", nullable = false)
    private Instant thisUpdate;

    @Column(name = "next_update", nullable = false)
    private Instant nextUpdate;

    @Builder.Default
    @Column(name = "revoked_count", nullable = false)
    private int revokedCount = 0;

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
