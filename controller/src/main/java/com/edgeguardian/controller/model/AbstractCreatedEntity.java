package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Base for entities that record their creation time. {@code createdAt} is stamped once on
 * insert and never updated.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractCreatedEntity extends AbstractEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
