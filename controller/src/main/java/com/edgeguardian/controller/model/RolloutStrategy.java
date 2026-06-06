package com.edgeguardian.controller.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Rollout strategy for an OTA deployment. Stored and serialized as a lowercase string.
 * Currently advisory: every strategy fans out to all matching devices at once.
 */
public enum RolloutStrategy {
    ROLLING,
    CANARY,
    IMMEDIATE;

    @JsonValue
    public String toDbValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static RolloutStrategy fromDbValue(String value) {
        if (value == null) return null;
        return valueOf(value.toUpperCase());
    }
}
