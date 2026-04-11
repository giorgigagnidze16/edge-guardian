package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "certificate_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "common_name", nullable = false)
    private String commonName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private List<String> sans = List.of();

    @Column(name = "csr_pem", nullable = false)
    private String csrPem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CertRequestType type = CertRequestType.INITIAL;

    @Column(name = "current_serial")
    private String currentSerial;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CertRequestState state = CertRequestState.PENDING;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
