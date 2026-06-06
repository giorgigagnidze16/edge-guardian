package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Base for entities that track both creation and last-modification time. On insert
 * {@code updatedAt} mirrors {@code createdAt}; every update bumps it to the current instant.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractAuditedEntity extends AbstractCreatedEntity {

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void stampUpdatedAt() {
        if (updatedAt == null) {
            updatedAt = getCreatedAt() != null ? getCreatedAt() : Instant.now();
        }
    }

    @PreUpdate
    void touchUpdatedAt() {
        updatedAt = Instant.now();
    }
}
