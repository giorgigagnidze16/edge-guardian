package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, Long> {

    List<OrganizationMember> findByOrganizationId(Long organizationId);

    List<OrganizationMember> findByUserId(Long userId);

    Optional<OrganizationMember> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    boolean existsByOrganizationIdAndUserId(Long organizationId, Long userId);

    void deleteByOrganizationIdAndUserId(Long organizationId, Long userId);
}
