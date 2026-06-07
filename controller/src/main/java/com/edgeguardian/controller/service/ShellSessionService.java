package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.ShellProperties;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.security.TenantPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policy and lifecycle for interactive device shell sessions: tenant + online
 * checks, one-time WebSocket tickets, concurrency limits, output routing, and
 * open/close audit. Transport-agnostic so it is unit-testable without sockets.
 */
@Service
public class ShellSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRegistry deviceRegistry;
    private final AuditService auditService;
    private final ShellProperties props;
    private final Clock clock;

    private final Map<String, ShellSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> ticketIndex = new ConcurrentHashMap<>();

    @Autowired
    public ShellSessionService(DeviceRegistry deviceRegistry, AuditService auditService, ShellProperties props) {
        this(deviceRegistry, auditService, props, Clock.systemUTC());
    }

    ShellSessionService(DeviceRegistry deviceRegistry, AuditService auditService,
                        ShellProperties props, Clock clock) {
        this.deviceRegistry = deviceRegistry;
        this.auditService = auditService;
        this.props = props;
        this.clock = clock;
    }

    /** One-time WebSocket ticket plus the session id it unlocks. */
    public record ShellTicket(String sessionId, String ticket) {}

    /**
     * Reserve a shell session for an online, in-org device and mint a one-time
     * ticket. Authorization (OPERATOR+) is enforced by the controller.
     */
    public ShellTicket create(TenantPrincipal principal, String deviceId, int rows, int cols) {
        Device device = deviceRegistry.findByIdForOrganization(deviceId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (device.getState() != Device.DeviceState.ONLINE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device is not online");
        }

        purgeExpired();
        enforceLimits(deviceId, principal.organizationId());

        Instant now = clock.instant();
        ShellSession session = new ShellSession(UUID.randomUUID().toString(), deviceId,
                principal.organizationId(), principal.userId(), now, rows, cols);
        session.ticket = newTicket();
        session.ticketExpiresAt = now.plus(props.ticketTtl());

        sessions.put(session.sessionId(), session);
        ticketIndex.put(session.ticket, session.sessionId());
        return new ShellTicket(session.sessionId(), session.ticket);
    }

    /**
     * Consume a ticket and attach the browser's output sink, marking the session
     * live and auditing {@code shell_opened}. The ticket is single-use.
     */
    public ShellSession activate(String ticket, ShellOutputSink sink) {
        String sessionId = ticketIndex.remove(ticket);
        ShellSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or already-used shell ticket");
        }
        if (clock.instant().isAfter(session.ticketExpiresAt)) {
            sessions.remove(sessionId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired shell ticket");
        }

        session.ticket = null;
        session.active = true;
        session.openedAt = clock.instant();
        session.sink = sink;

        auditService.log(session.organizationId(), session.userId(), "shell_opened", "device",
                session.deviceId(), Map.of("sessionId", sessionId));
        return session;
    }

    /** Route device output bytes to the browser, if the session is live. */
    public void deliverOutput(String sessionId, byte[] data) {
        ShellSession session = sessions.get(sessionId);
        if (session != null && session.sink != null) {
            session.sink.send(data);
        }
    }

    public Optional<ShellSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Tear down a session, auditing {@code shell_closed}. Idempotent. */
    public void close(String sessionId, String reason) {
        ShellSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        if (session.ticket != null) {
            ticketIndex.remove(session.ticket);
        }
        if (session.active) {
            long durationMs = Duration.between(session.openedAt, clock.instant()).toMillis();
            auditService.log(session.organizationId(), session.userId(), "shell_closed", "device",
                    session.deviceId(), Map.of(
                            "sessionId", sessionId,
                            "durationMs", durationMs,
                            "reason", reason == null ? "" : reason));
        }
    }

    private void enforceLimits(String deviceId, Long organizationId) {
        if (countBy(s -> s.deviceId().equals(deviceId)) >= props.maxSessionsPerDevice()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many active shell sessions for this device");
        }
        if (countBy(s -> s.organizationId().equals(organizationId)) >= props.maxSessionsPerOrg()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many active shell sessions for this organization");
        }
    }

    private long countBy(java.util.function.Predicate<ShellSession> filter) {
        return sessions.values().stream().filter(filter).count();
    }

    /** Drop pending sessions whose ticket expired before activation. */
    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> {
            if (s.active || s.ticketExpiresAt == null || !now.isAfter(s.ticketExpiresAt)) {
                return false;
            }
            if (s.ticket != null) {
                ticketIndex.remove(s.ticket);
            }
            return true;
        });
    }

    private static String newTicket() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
