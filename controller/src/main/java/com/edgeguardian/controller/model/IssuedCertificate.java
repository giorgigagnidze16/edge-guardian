package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "certificates")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IssuedCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "common_name", nullable = false)
    private String commonName;

    @Column(name = "serial_number", nullable = false, unique = true)
    private String serialNumber;

    @Column(name = "cert_pem", nullable = false)
    private String certPem;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "not_after", nullable = false)
    private Instant notAfter;

    @Column(name = "replaced_by")
    private Long replacedBy;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
