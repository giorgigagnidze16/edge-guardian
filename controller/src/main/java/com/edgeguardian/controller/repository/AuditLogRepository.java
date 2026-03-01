package com.edgeguardian.controller.repository;

import com.edgeguardian.controller.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);
}
