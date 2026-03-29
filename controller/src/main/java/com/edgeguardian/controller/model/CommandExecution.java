package com.edgeguardian.controller.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Stores command execution results reported by agents.
 */
@Entity
@Table(name = "command_executions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommandExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false)
    private String commandId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private String phase;

    @Column(nullable = false)
    private String status;

    @Column(name = "exit_code", nullable = false)
    @Builder.Default
    private int exitCode = 0;

    @Column(columnDefinition = "TEXT")
    private String stdout;

    @Column(columnDefinition = "TEXT")
    private String stderr;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    @Builder.Default
    private long durationMs = 0;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @PrePersist
    protected void onCreate() {
        this.receivedAt = Instant.now();
    }
}
