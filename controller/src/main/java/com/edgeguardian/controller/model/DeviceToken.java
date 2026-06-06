package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "device_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceToken extends AbstractEntity {

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false)
    private String tokenPrefix;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    @PrePersist
    void stampIssuedAt() {
        if (issuedAt == null) {
            issuedAt = Instant.now();
        }
    }
}
