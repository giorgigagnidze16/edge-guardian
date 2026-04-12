package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.CertRequestState;
import com.edgeguardian.controller.model.CertificateRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CertificateRequestRepository extends JpaRepository<CertificateRequest, Long> {

    List<CertificateRequest> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<CertificateRequest> findByOrganizationIdAndStateOrderByCreatedAtDesc(
            Long organizationId, CertRequestState state);

    List<CertificateRequest> findByDeviceIdAndStateIn(
            String deviceId, Collection<CertRequestState> states);
}
