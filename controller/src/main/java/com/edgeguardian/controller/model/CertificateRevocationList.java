package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "certificate_revocation_lists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateRevocationList extends AbstractAuditedEntity {

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
}
