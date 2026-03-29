package com.edgeguardian.controller.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * State of an OTA deployment on a specific device.
 * Values are stored and serialized as lowercase strings (e.g. "pending", "downloading").
 */
public enum OtaDeviceState {
    PENDING,
    DOWNLOADING,
    VERIFYING,
    APPLYING,
    COMPLETED,
    FAILED,
    ROLLED_BACK;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ROLLED_BACK;
    }

    @JsonValue
    public String toDbValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static OtaDeviceState fromDbValue(String value) {
        if (value == null) return null;
        return valueOf(value.toUpperCase());
    }
}
