package com.edgeguardian.controller.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * State of an OTA deployment rollout.
 * Values are stored and serialized as lowercase strings (e.g. "pending", "in_progress").
 */
public enum DeploymentState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    @JsonValue
    public String toDbValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static DeploymentState fromDbValue(String value) {
        if (value == null) return null;
        return valueOf(value.toUpperCase());
    }
}
