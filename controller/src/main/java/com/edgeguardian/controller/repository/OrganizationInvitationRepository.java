package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.OrganizationInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, Long> {

    List<OrganizationInvitation> findByEmail(String email);

    List<OrganizationInvitation> findByOrganizationId(Long organizationId);

    Optional<OrganizationInvitation> findByOrganizationIdAndEmail(Long organizationId, String email);
}
