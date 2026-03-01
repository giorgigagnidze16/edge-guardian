package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "enrollment_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrollmentToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String name;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private int useCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isValid() {
        if (revoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses != null && useCount >= maxUses) return false;
        return true;
    }
}
