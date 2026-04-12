package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifecycleService {

    private static final String REJECT_REASON = "Device deleted";

    private final AuditService auditService;
    private final DeviceRegistry deviceRegistry;
    private final CertificateService certificateService;
    private final DeviceTokenRepository deviceTokenRepository;

    @Transactional
    public void deleteDevice(String deviceId, Long actorUserId) {
        Device device = deviceRegistry.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found: " + deviceId));

        Long orgId = device.getOrganizationId();

        int revoked = certificateService.revokeAllActiveForDevice(
                deviceId, orgId, RevokeReason.DEVICE_DELETED, actorUserId);
        int rejected = certificateService.rejectPendingRequestsForDevice(
                deviceId, orgId, REJECT_REASON, actorUserId);

        deviceTokenRepository.deleteByDeviceId(deviceId);

        if (!deviceRegistry.remove(deviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceId);
        }

        auditService.log(orgId, actorUserId, "device_deleted", "device", deviceId,
                Map.of("certsRevoked", revoked, "requestsRejected", rejected));

        log.info("Device {} deleted (org={}, certsRevoked={}, requestsRejected={})",
                deviceId, orgId, revoked, rejected);
    }
}
