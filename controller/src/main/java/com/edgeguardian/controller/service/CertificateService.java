package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.CaProperties;
import com.edgeguardian.controller.model.*;
import com.edgeguardian.controller.repository.CertificateRequestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateRequestRepository requestRepository;
    private final IssuedCertificateRepository certRepository;
    private final CertificateAuthorityService caService;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;
    private final CaProperties caProperties;

    /**
     * Process an incoming certificate request from a device.
     *
     * Security model:
     *  - RENEWAL: must prove ownership via currentSerial → auto-approved, old cert rotated.
     *  - INITIAL / MANIFEST with existing valid cert → BLOCKED (compromise detection).
     *  - MANIFEST without existing cert → auto-approved (desired-state driven).
     *  - INITIAL without existing cert → PENDING (requires manual approval).
     *
     * After a BLOCK, admin must revoke the old certs and un-suspend the device
     * before the device can re-request (which will then land in PENDING).
     */
    @Transactional
    public CertRequestResult processRequest(String deviceId, Long orgId, String name,
                                            String commonName, List<String> sans,
                                            String csrPem, CertRequestType type,
                                            String currentSerial) {
        if (type == CertRequestType.RENEWAL) {
            return processRenewal(deviceId, orgId, name, commonName, sans, csrPem, currentSerial);
        }

        // Compromise detection: if device already holds a valid cert for this name,
        // any non-renewal request is suspicious — block and suspend.
        List<IssuedCertificate> activeCerts = certRepository
                .findByDeviceIdAndNameAndRevokedFalseAndNotAfterAfter(deviceId, name, Instant.now());

        if (!activeCerts.isEmpty()) {
            return blockAsCompromised(deviceId, orgId, name, commonName, sans, csrPem, type,
                    activeCerts.size());
        }

        // No existing valid cert — safe to proceed.
        CertificateRequest request = saveRequest(deviceId, orgId, name, commonName, sans,
                csrPem, type, null);

        if (type == CertRequestType.MANIFEST) {
            IssuedCertificate cert = signAndIssue(request);
            log.info("Auto-approved manifest cert for device {} cert '{}'", deviceId, name);
            return new CertRequestResult(request, cert, false);
        }

        log.info("Cert request pending approval for device {} cert '{}'", deviceId, name);
        return new CertRequestResult(request, null, false);
    }

    private CertRequestResult processRenewal(String deviceId, Long orgId, String name,
                                             String commonName, List<String> sans,
                                             String csrPem, String currentSerial) {
        if (currentSerial == null || currentSerial.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Renewal request must include currentSerial");
        }

        IssuedCertificate currentCert = certRepository.findBySerialNumber(currentSerial)
                .orElse(null);

        if (currentCert == null || currentCert.isRevoked()
                || !currentCert.getDeviceId().equals(deviceId)
                || !currentCert.getName().equals(name)) {
            log.warn("Renewal rejected for device {}: serial {} not valid", deviceId, currentSerial);
            CertificateRequest rejected = saveRequest(deviceId, orgId, name, commonName, sans,
                    csrPem, CertRequestType.RENEWAL, currentSerial);
            rejected.setState(CertRequestState.REJECTED);
            rejected.setRejectReason("Cannot verify current certificate ownership");
            requestRepository.save(rejected);
            return new CertRequestResult(rejected, null, false);
        }

        CertificateRequest request = saveRequest(deviceId, orgId, name, commonName, sans,
                csrPem, CertRequestType.RENEWAL, currentSerial);
        IssuedCertificate newCert = signAndIssue(request);

        currentCert.setRevoked(true);
        currentCert.setRevokedAt(Instant.now());
        currentCert.setRevokeReason(RevokeReason.RENEWED);
        currentCert.setReplacedBy(newCert.getId());
        certRepository.save(currentCert);

        log.info("Auto-approved renewal for device {} cert '{}', old serial {} -> new serial {}",
                deviceId, name, currentSerial, newCert.getSerialNumber());
        return new CertRequestResult(request, newCert, false);
    }

    private CertRequestResult blockAsCompromised(String deviceId, Long orgId, String name,
                                                 String commonName, List<String> sans,
                                                 String csrPem, CertRequestType type,
                                                 int activeCertCount) {
        log.warn("SECURITY: Device {} requested cert '{}' (type={}) but already has {} valid cert(s). "
                 + "Blocking and revoking.", deviceId, name, type, activeCertCount);

        revokeAllForDevice(deviceId, RevokeReason.COMPROMISED);
        suspendDevice(deviceId);

        CertificateRequest blocked = requestRepository.save(CertificateRequest.builder()
                .deviceId(deviceId)
                .organizationId(orgId)
                .name(name)
                .commonName(commonName)
                .sans(sans)
                .csrPem(csrPem)
                .type(type)
                .state(CertRequestState.BLOCKED)
                .rejectReason("Device already has valid certificate. Possible compromise.")
                .build());

        auditService.log(orgId, null, "cert_request_blocked", "certificate_request",
                blocked.getId().toString(),
                Map.of("deviceId", deviceId, "name", name,
                        "type", type.name(), "reason", "duplicate_request"));

        return new CertRequestResult(blocked, null, true);
    }

    @Transactional
    public IssuedCertificate approve(Long requestId, Long reviewerUserId) {
        CertificateRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (request.getState() != CertRequestState.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request is not pending: " + request.getState());
        }

        request.setReviewedBy(reviewerUserId);
        request.setReviewedAt(Instant.now());
        requestRepository.save(request);

        IssuedCertificate cert = signAndIssue(request);

        auditService.log(request.getOrganizationId(), reviewerUserId, "cert_approved",
                "certificate_request", requestId.toString(),
                Map.of("deviceId", request.getDeviceId(), "name", request.getName(),
                        "serial", cert.getSerialNumber()));

        return cert;
    }

    @Transactional
    public void reject(Long requestId, Long reviewerUserId, String reason) {
        CertificateRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (request.getState() != CertRequestState.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request is not pending: " + request.getState());
        }

        request.setState(CertRequestState.REJECTED);
        request.setRejectReason(reason);
        request.setReviewedBy(reviewerUserId);
        request.setReviewedAt(Instant.now());
        requestRepository.save(request);

        auditService.log(request.getOrganizationId(), reviewerUserId, "cert_rejected",
                "certificate_request", requestId.toString(),
                Map.of("deviceId", request.getDeviceId(), "reason", reason != null ? reason : ""));
    }

    @Transactional
    public void revoke(Long certificateId, Long userId) {
        IssuedCertificate cert = certRepository.findById(certificateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found"));

        if (cert.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Certificate already revoked");
        }

        cert.setRevoked(true);
        cert.setRevokedAt(Instant.now());
        cert.setRevokeReason(RevokeReason.ADMIN_REVOKED);
        certRepository.save(cert);

        auditService.log(cert.getOrganizationId(), userId, "cert_revoked", "certificate",
                certificateId.toString(),
                Map.of("deviceId", cert.getDeviceId(), "serial", cert.getSerialNumber()));
    }

    @Transactional(readOnly = true)
    public List<CertificateRequest> findRequestsByOrganization(Long orgId) {
        return requestRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional(readOnly = true)
    public List<CertificateRequest> findPendingRequests(Long orgId) {
        return requestRepository.findByOrganizationIdAndStateOrderByCreatedAtDesc(
                orgId, CertRequestState.PENDING);
    }

    @Transactional(readOnly = true)
    public List<IssuedCertificate> findCertificatesByOrganization(Long orgId) {
        return certRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    private CertificateRequest saveRequest(String deviceId, Long orgId, String name,
                                             String commonName, List<String> sans,
                                             String csrPem, CertRequestType type,
                                             String currentSerial) {
        return requestRepository.save(CertificateRequest.builder()
                .deviceId(deviceId)
                .organizationId(orgId)
                .name(name)
                .commonName(commonName)
                .sans(sans)
                .csrPem(csrPem)
                .type(type)
                .currentSerial(currentSerial)
                .state(CertRequestState.PENDING)
                .build());
    }

    private IssuedCertificate signAndIssue(CertificateRequest request) {
        try {
            var result = caService.signCsr(
                    request.getOrganizationId(),
                    request.getCsrPem(),
                    caProperties.certValidityDays(),
                    request.getSans()
            );

            request.setState(CertRequestState.APPROVED);
            if (request.getReviewedAt() == null) {
                request.setReviewedAt(Instant.now());
            }
            requestRepository.save(request);

            return certRepository.save(IssuedCertificate.builder()
                    .requestId(request.getId())
                    .deviceId(request.getDeviceId())
                    .organizationId(request.getOrganizationId())
                    .name(request.getName())
                    .commonName(request.getCommonName())
                    .serialNumber(result.serialNumber())
                    .certPem(result.certPem())
                    .notBefore(result.notBefore())
                    .notAfter(result.notAfter())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign CSR for device " + request.getDeviceId(), e);
        }
    }

    private void revokeAllForDevice(String deviceId, RevokeReason reason) {
        List<IssuedCertificate> certs = certRepository
                .findByDeviceIdAndRevokedFalseAndNotAfterAfter(deviceId, Instant.now());
        Instant now = Instant.now();
        for (IssuedCertificate cert : certs) {
            cert.setRevoked(true);
            cert.setRevokedAt(now);
            cert.setRevokeReason(reason);
        }
        certRepository.saveAll(certs);
    }

    private void suspendDevice(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            device.setState(Device.DeviceState.SUSPENDED);
            deviceRepository.save(device);
            log.warn("Device {} suspended due to potential compromise", deviceId);
        });
    }

    public record CertRequestResult(CertificateRequest request, IssuedCertificate certificate,
                                    boolean blocked) {}
}
