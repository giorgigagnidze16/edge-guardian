package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edgeguardian.controller.config.ShellProperties;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.OrgRole;
import com.edgeguardian.controller.security.TenantPrincipal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit test for ShellSessionService: ticket lifecycle, session limits, and
 * audit emission, with DeviceRegistry/AuditService mocked.
 */
@ExtendWith(MockitoExtension.class)
class ShellSessionServiceTest {

    private static final String DEVICE = "rpi-001";
    private static final Long ORG = 42L;
    private static final Long USER = 7L;

    @Mock private DeviceRegistry deviceRegistry;
    @Mock private AuditService auditService;

    private ShellSessionService service;
    private TenantPrincipal operator;

    @BeforeEach
    void setUp() {
        ShellProperties props = new ShellProperties(2, 10,
                Duration.ofMinutes(15), Duration.ofMinutes(60), Duration.ofSeconds(30));
        service = new ShellSessionService(deviceRegistry, auditService, props);
        operator = new TenantPrincipal(ORG, USER, "alice", OrgRole.OPERATOR);
    }

    private void deviceOnline() {
        when(deviceRegistry.findByIdForOrganization(DEVICE, ORG)).thenReturn(Optional.of(
                Device.builder().deviceId(DEVICE).organizationId(ORG)
                        .state(Device.DeviceState.ONLINE).build()));
    }

    @Test
    void create_returnsSessionAndTicket_whenDeviceOnline() {
        deviceOnline();
        var resp = service.create(operator, DEVICE, 24, 80);
        assertThat(resp.sessionId()).isNotBlank();
        assertThat(resp.ticket()).isNotBlank();
        assertThat(service.get(resp.sessionId())).isPresent();
    }

    @Test
    void create_throwsNotFound_whenDeviceNotInOrg() {
        when(deviceRegistry.findByIdForOrganization(DEVICE, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(operator, DEVICE, 24, 80))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void create_throwsConflict_whenDeviceOffline() {
        when(deviceRegistry.findByIdForOrganization(DEVICE, ORG)).thenReturn(Optional.of(
                Device.builder().deviceId(DEVICE).organizationId(ORG)
                        .state(Device.DeviceState.OFFLINE).build()));
        assertThatThrownBy(() -> service.create(operator, DEVICE, 24, 80))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void create_enforcesPerDeviceLimit() {
        deviceOnline();
        service.create(operator, DEVICE, 24, 80);
        service.create(operator, DEVICE, 24, 80); // reaches max of 2
        assertThatThrownBy(() -> service.create(operator, DEVICE, 24, 80))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void activate_marksActiveAndAuditsOpened() {
        deviceOnline();
        var resp = service.create(operator, DEVICE, 24, 80);
        var session = service.activate(resp.ticket(), data -> {});
        assertThat(session.sessionId()).isEqualTo(resp.sessionId());
        assertThat(session.deviceId()).isEqualTo(DEVICE);
        verify(auditService).log(eq(ORG), eq(USER), eq("shell_opened"), eq("device"), eq(DEVICE), any());
    }

    @Test
    void activate_rejectsReusedTicket() {
        deviceOnline();
        var resp = service.create(operator, DEVICE, 24, 80);
        service.activate(resp.ticket(), d -> {});
        assertThatThrownBy(() -> service.activate(resp.ticket(), d -> {}))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void activate_rejectsUnknownTicket() {
        assertThatThrownBy(() -> service.activate("does-not-exist", d -> {}))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deliverOutput_routesBytesToSink() {
        deviceOnline();
        var resp = service.create(operator, DEVICE, 24, 80);
        AtomicReference<byte[]> received = new AtomicReference<>();
        service.activate(resp.ticket(), received::set);

        service.deliverOutput(resp.sessionId(), "device-bytes".getBytes());

        assertThat(received.get()).isEqualTo("device-bytes".getBytes());
    }

    @Test
    void close_auditsClosedAndRemovesSession() {
        deviceOnline();
        var resp = service.create(operator, DEVICE, 24, 80);
        service.activate(resp.ticket(), d -> {});

        service.close(resp.sessionId(), "client_disconnect");

        assertThat(service.get(resp.sessionId())).isEmpty();
        verify(auditService).log(eq(ORG), eq(USER), eq("shell_closed"), eq("device"), eq(DEVICE), any());
    }
}
