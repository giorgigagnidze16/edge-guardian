package com.edgeguardian.controller.service;

import java.time.Instant;

/**
 * A live (or pending) interactive shell session. Immutable identity; lifecycle
 * fields are managed solely by {@link ShellSessionService}.
 */
public final class ShellSession {

    private final String sessionId;
    private final String deviceId;
    private final Long organizationId;
    private final Long userId;
    private final Instant createdAt;
    private final int rows;
    private final int cols;

    // Lifecycle state, mutated only by ShellSessionService (same package).
    volatile String ticket;
    volatile Instant ticketExpiresAt;
    volatile boolean active;
    volatile Instant openedAt;
    volatile ShellOutputSink sink;

    ShellSession(String sessionId, String deviceId, Long organizationId, Long userId,
                 Instant createdAt, int rows, int cols) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.rows = rows;
        this.cols = cols;
    }

    public String sessionId() { return sessionId; }
    public String deviceId() { return deviceId; }
    public Long organizationId() { return organizationId; }
    public Long userId() { return userId; }
    public Instant createdAt() { return createdAt; }
    public int rows() { return rows; }
    public int cols() { return cols; }
}
