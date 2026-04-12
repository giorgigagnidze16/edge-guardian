package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit test for DeviceLifecycleService — verifies the orchestration contract
 * (who gets called, in what order, and with what arguments) without spinning up Spring.
 */
@ExtendWith(MockitoExtension.class)
class DeviceLifecycleServiceTest {

    private static final String DEVICE_ID = "rpi-001";
    private static final Long ORG_ID = 42L;
    private static final Long ACTOR_USER_ID = 7L;

    @Mock private DeviceRegistry deviceRegistry;
    @Mock private CertificateService certificateService;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private AuditService auditService;

    @InjectMocks private DeviceLifecycleService lifecycle;

    private Device device;

    @BeforeEach
    void setUp() {
        device = Device.builder()
                .deviceId(DEVICE_ID)
                .organizationId(ORG_ID)
                .hostname("host")
                .architecture("arm64")
                .os("linux")
                .agentVersion("0.4.0")
                .build();
    }

    @Test
    void deleteDevice_cascadesThroughAllCollaboratorsInOrder() {
        when(deviceRegistry.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(certificateService.revokeAllActiveForDevice(
                DEVICE_ID, ORG_ID, RevokeReason.DEVICE_DELETED, ACTOR_USER_ID)).thenReturn(2);
        when(certificateService.rejectPendingRequestsForDevice(
                eq(DEVICE_ID), eq(ORG_ID), any(), eq(ACTOR_USER_ID))).thenReturn(1);
        when(deviceRegistry.remove(DEVICE_ID)).thenReturn(true);

        lifecycle.deleteDevice(DEVICE_ID, ACTOR_USER_ID);

        InOrder inOrder = Mockito.inOrder(
                deviceRegistry, certificateService, deviceTokenRepository, auditService);
        inOrder.verify(deviceRegistry).findById(DEVICE_ID);
        inOrder.verify(certificateService).revokeAllActiveForDevice(
                DEVICE_ID, ORG_ID, RevokeReason.DEVICE_DELETED, ACTOR_USER_ID);
        inOrder.verify(certificateService).rejectPendingRequestsForDevice(
                eq(DEVICE_ID), eq(ORG_ID), any(), eq(ACTOR_USER_ID));
        inOrder.verify(deviceTokenRepository).deleteByDeviceId(DEVICE_ID);
        inOrder.verify(deviceRegistry).remove(DEVICE_ID);
        inOrder.verify(auditService).log(
                eq(ORG_ID), eq(ACTOR_USER_ID), eq("device_deleted"),
                eq("device"), eq(DEVICE_ID), any(Map.class));
    }

    @Test
    void deleteDevice_missingDevice_throws404AndTouchesNothingElse() {
        when(deviceRegistry.findById(DEVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycle.deleteDevice(DEVICE_ID, ACTOR_USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(certificateService);
        verifyNoInteractions(deviceTokenRepository);
        verifyNoInteractions(auditService);
        verify(deviceRegistry, never()).remove(anyString());
    }

    @Test
    void deleteDevice_auditCapturesCountsFromCollaborators() {
        when(deviceRegistry.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(certificateService.revokeAllActiveForDevice(
                DEVICE_ID, ORG_ID, RevokeReason.DEVICE_DELETED, ACTOR_USER_ID)).thenReturn(3);
        when(certificateService.rejectPendingRequestsForDevice(
                eq(DEVICE_ID), eq(ORG_ID), any(), eq(ACTOR_USER_ID))).thenReturn(5);
        when(deviceRegistry.remove(DEVICE_ID)).thenReturn(true);

        lifecycle.deleteDevice(DEVICE_ID, ACTOR_USER_ID);

        verify(auditService).log(
                eq(ORG_ID), eq(ACTOR_USER_ID), eq("device_deleted"),
                eq("device"), eq(DEVICE_ID),
                eq(Map.of("certsRevoked", 3, "requestsRejected", 5)));
    }

    @Test
    void deleteDevice_concurrentDelete_surfacesAs404() {
        // findById succeeds but remove() returns false — another actor raced us.
        when(deviceRegistry.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(deviceRegistry.remove(DEVICE_ID)).thenReturn(false);

        assertThatThrownBy(() -> lifecycle.deleteDevice(DEVICE_ID, ACTOR_USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Audit should NOT fire if removal didn't happen — avoids lying to the log.
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteDevice_noActiveCertsOrRequests_stillRemovesAndAudits() {
        when(deviceRegistry.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(certificateService.revokeAllActiveForDevice(
                DEVICE_ID, ORG_ID, RevokeReason.DEVICE_DELETED, ACTOR_USER_ID)).thenReturn(0);
        when(certificateService.rejectPendingRequestsForDevice(
                eq(DEVICE_ID), eq(ORG_ID), any(), eq(ACTOR_USER_ID))).thenReturn(0);
        when(deviceRegistry.remove(DEVICE_ID)).thenReturn(true);

        lifecycle.deleteDevice(DEVICE_ID, ACTOR_USER_ID);

        verify(deviceTokenRepository).deleteByDeviceId(DEVICE_ID);
        verify(deviceRegistry).remove(DEVICE_ID);
        verify(auditService).log(
                eq(ORG_ID), eq(ACTOR_USER_ID), eq("device_deleted"),
                eq("device"), eq(DEVICE_ID),
                eq(Map.of("certsRevoked", 0, "requestsRejected", 0)));
    }
}
