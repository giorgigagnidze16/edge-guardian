package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.EnrollmentToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, Long> {

    Optional<EnrollmentToken> findByToken(String token);

    List<EnrollmentToken> findByOrganizationId(Long organizationId);
}
