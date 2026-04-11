package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "organization_cas")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationCa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Column(name = "ca_cert_pem", nullable = false)
    private String caCertPem;

    @Column(name = "ca_key_encrypted", nullable = false)
    private byte[] caKeyEncrypted;

    @Column(name = "ca_key_iv", nullable = false)
    private byte[] caKeyIv;

    @Column(nullable = false)
    private String subject;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "not_after", nullable = false)
    private Instant notAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
