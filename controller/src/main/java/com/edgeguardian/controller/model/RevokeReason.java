package com.edgeguardian.controller.model;

public enum RevokeReason {
    RENEWED, ADMIN_REVOKED, COMPROMISED, EXPIRED, DEVICE_DELETED,
    /**
     * The device re-enrolled and a fresh cert was issued with the same name.
     * Emitted when the old cert is auto-revoked as part of accepting the new
     * enrollment request (covers legitimate re-installs, factory resets,
     * identity directory wipes). Admins should still watch the audit log for
     * unexpected re-enrollments on production devices.
     */
    SUPERSEDED
}
