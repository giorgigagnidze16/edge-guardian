package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.OrganizationCa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationCaRepository extends JpaRepository<OrganizationCa, Long> {

    Optional<OrganizationCa> findByOrganizationId(Long organizationId);
}
