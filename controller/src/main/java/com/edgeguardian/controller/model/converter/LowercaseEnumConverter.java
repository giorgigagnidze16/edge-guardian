package com.edgeguardian.controller.model.converter;

import jakarta.persistence.AttributeConverter;

/**
 * Persists an enum as its lowercase name ({@code IN_PROGRESS} &lt;-&gt; {@code "in_progress"}).
 * Subclasses bind the concrete enum type and add {@code @Converter(autoApply = true)}.
 */
abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected LowercaseEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name().toLowerCase();
    }

    @Override
    public E convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : Enum.valueOf(type, dbValue.toUpperCase());
    }
}
