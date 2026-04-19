package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.CaProperties;
import com.edgeguardian.controller.model.CertRequestState;
import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.CertificateRequest;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.CertificateRequestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import com.edgeguardian.controller.service.result.CertRequestResult;
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
    private final CrlService crlService;
    private final EmqxAdminClient emqxAdminClient;

    @Transactional
    public CertRequestResult processRequest(String deviceId, Long orgId, String name,
                                            String commonName, List<String> sans,
                                            String csrPem, CertRequestType type,
                                            String currentSerial) {
        return switch (type) {
            case INITIAL -> processInitial(deviceId, orgId, name, commonName, sans, csrPem);
            case RENEWAL -> processRenewal(deviceId, orgId, name, commonName, sans, csrPem, currentSerial);
            case MANIFEST -> processManifest(deviceId, orgId, name, commonName, sans, csrPem);
        };
    }

    // Compromise check before any non-renewal issuance.
    private CertRequestResult processNonRenewal(String deviceId, Long orgId, String name,
                                                String commonName, List<String> sans,
                                                String csrPem, CertRequestType type,
                                                boolean autoApprove) {
        List<IssuedCertificate> activeCerts = certRepository
                .findByDeviceIdAndNameAndRevokedFalseAndNotAfterAfter(deviceId, name, Instant.now());
        if (!activeCerts.isEmpty()) {
            return blockAsCompromised(deviceId, orgId, name, commonName, sans, csrPem, type,
                    activeCerts.size());
        }

        CertificateRequest request = saveRequest(deviceId, orgId, name, commonName, sans,
                csrPem, type, null);
        if (autoApprove) {
            IssuedCertificate cert = signAndIssue(request);
            log.info("Auto-approved {} cert for device {} cert '{}'", type, deviceId, name);
            return new CertRequestResult(request, cert, false);
        }
        log.info("Cert request pending approval for device {} cert '{}'", deviceId, name);
        return new CertRequestResult(request, null, false);
    }

    private CertRequestResult processInitial(String deviceId, Long orgId, String name,
                                             String commonName, List<String> sans, String csrPem) {
        return processNonRenewal(deviceId, orgId, name, commonName, sans, csrPem,
                CertRequestType.INITIAL, false);
    }

    private CertRequestResult processManifest(String deviceId, Long orgId, String name,
                                              String commonName, List<String> sans, String csrPem) {
        return processNonRenewal(deviceId, orgId, name, commonName, sans, csrPem,
                CertRequestType.MANIFEST, true);
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

        crlService.rebuild(orgId);
        // No kickout: the agent is mid-renewal and will reconnect with the new cert.

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

        crlService.rebuild(orgId);
        emqxAdminClient.kickout(deviceId);

        return new CertRequestResult(blocked, null, true);
    }

    @Transactional
    public IssuedCertificate approve(Long requestId, Long expectedOrgId, Long reviewerUserId) {
        CertificateRequest request = loadRequestForOrg(requestId, expectedOrgId);

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
    public void reject(Long requestId, Long expectedOrgId, Long reviewerUserId, String reason) {
        CertificateRequest request = loadRequestForOrg(requestId, expectedOrgId);

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
    public void revoke(Long certificateId, Long expectedOrgId, Long userId) {
        IssuedCertificate cert = certRepository.findById(certificateId)
                .filter(c -> expectedOrgId.equals(c.getOrganizationId()))
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

        crlService.rebuild(cert.getOrganizationId());
        emqxAdminClient.kickout(cert.getDeviceId());
    }

    private CertificateRequest loadRequestForOrg(Long requestId, Long expectedOrgId) {
        return requestRepository.findById(requestId)
                .filter(r -> expectedOrgId.equals(r.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    @Transactional
    public int revokeAllActiveForDevice(String deviceId, Long orgId, RevokeReason reason,
                                        Long actorUserId) {
        List<IssuedCertificate> active = certRepository
                .findByDeviceIdAndRevokedFalseAndNotAfterAfter(deviceId, Instant.now());
        if (active.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        for (IssuedCertificate cert : active) {
            cert.setRevoked(true);
            cert.setRevokedAt(now);
            cert.setRevokeReason(reason);
        }
        certRepository.saveAll(active);

        List<String> serials = active.stream().map(IssuedCertificate::getSerialNumber).toList();
        auditService.log(orgId, actorUserId, "certs_revoked_for_device", "device", deviceId,
                Map.of("count", active.size(), "reason", reason.name(), "serials", serials));

        crlService.rebuild(orgId);
        emqxAdminClient.kickout(deviceId);

        log.info("Revoked {} certificate(s) for device {} (reason={})",
                active.size(), deviceId, reason);
        return active.size();
    }

    @Transactional
    public int rejectPendingRequestsForDevice(String deviceId, Long orgId, String reason,
                                              Long actorUserId) {
        List<CertificateRequest> pending = requestRepository
                .findByDeviceIdAndStateIn(deviceId, List.of(CertRequestState.PENDING));
        if (pending.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        for (CertificateRequest req : pending) {
            req.setState(CertRequestState.REJECTED);
            req.setRejectReason(reason);
            req.setReviewedBy(actorUserId);
            req.setReviewedAt(now);
        }
        requestRepository.saveAll(pending);

        auditService.log(orgId, actorUserId, "cert_requests_rejected_for_device",
                "device", deviceId,
                Map.of("count", pending.size(), "reason", reason == null ? "" : reason));

        log.info("Rejected {} pending cert request(s) for device {} (reason={})",
                pending.size(), deviceId, reason);
        return pending.size();
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

}
