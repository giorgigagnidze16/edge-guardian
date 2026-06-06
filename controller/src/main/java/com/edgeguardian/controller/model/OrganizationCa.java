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
@Table(name = "organization_cas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationCa extends AbstractCreatedEntity {

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
}
