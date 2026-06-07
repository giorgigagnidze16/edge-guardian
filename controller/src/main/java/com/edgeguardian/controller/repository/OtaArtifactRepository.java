package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.OtaArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OtaArtifactRepository extends JpaRepository<OtaArtifact, Long> {

    List<OtaArtifact> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
