package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.OtaArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OtaArtifactRepository extends JpaRepository<OtaArtifact, Long> {

    List<OtaArtifact> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<OtaArtifact> findFirstByOrganizationIdAndVersionAndArchitecture(
            Long organizationId, String version, String architecture);
}
