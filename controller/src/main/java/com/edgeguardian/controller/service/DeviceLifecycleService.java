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

/**
 * Coordinates the multi-aggregate "delete device" operation.
 *
 * <p>Deleting a device is more than removing a row from {@code devices} — every credential that
 * identifies it (leaf certificates, pending cert requests, the bootstrap {@code DeviceToken})
 * must be invalidated so the identity cannot be reused by a rogue process that still holds
 * the private key. This service owns that fan-out and emits a single audit entry for the
 * operator action, while delegating the aggregate-level writes to their owning services
 * ({@link DeviceRegistry}, {@link CertificateService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLifecycleService {

    private static final String REJECT_REASON = "Device deleted";

    private final AuditService auditService;
    private final DeviceRegistry deviceRegistry;
    private final CertificateService certificateService;
    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Decommission and delete a device.
     *
     * <p>Order of operations (all inside a single transaction):
     * <ol>
     *   <li>Revoke every active leaf certificate → cert is unusable against the org CA from here on.</li>
     *   <li>Reject every pending certificate request → queued approvals can't produce new certs.</li>
     *   <li>Delete the {@code X-Device-Token} → agent endpoint auth fails immediately.</li>
     *   <li>Remove device + manifest rows.</li>
     *   <li>Write a {@code device_deleted} audit entry.</li>
     * </ol>
     *
     * @throws ResponseStatusException 404 if the device does not exist
     */
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
