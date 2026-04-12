package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.CertificateRevocationList;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificateRevocationListRepository
        extends JpaRepository<CertificateRevocationList, Long> {

    Optional<CertificateRevocationList> findByOrganizationId(Long organizationId);
}
