package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.IssuedCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IssuedCertificateRepository extends JpaRepository<IssuedCertificate, Long> {

    List<IssuedCertificate> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<IssuedCertificate> findByDeviceIdOrderByCreatedAtDesc(String deviceId);

    List<IssuedCertificate> findByDeviceIdAndRevokedFalseAndNotAfterAfter(
            String deviceId, Instant now);

    List<IssuedCertificate> findByDeviceIdAndNameAndRevokedFalseAndNotAfterAfter(
            String deviceId, String name, Instant now);

    Optional<IssuedCertificate> findBySerialNumber(String serialNumber);
}
