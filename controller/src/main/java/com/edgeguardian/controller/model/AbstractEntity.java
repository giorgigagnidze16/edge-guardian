package com.edgeguardian.controller.model;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.proxy.HibernateProxy;

/**
 * Base for entities with a generated surrogate {@code Long} key. Provides identity-based
 * {@code equals}/{@code hashCode} that are stable across the persistence lifecycle and
 * Hibernate proxies - two instances are equal only when both have the same non-null id.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || effectiveClass(this) != effectiveClass(o)) {
            return false;
        }
        Long otherId = ((AbstractEntity) o).getId();
        return id != null && id.equals(otherId);
    }

    @Override
    public final int hashCode() {
        return effectiveClass(this).hashCode();
    }

    private static Class<?> effectiveClass(Object o) {
        return o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
    }
}
