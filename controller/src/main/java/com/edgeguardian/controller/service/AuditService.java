package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.AuditLog;
import com.edgeguardian.controller.repository.AuditLogRepository;
import com.edgeguardian.controller.security.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void log(String action, String resourceType, String resourceId,
                    Map<String, Object> details, String ipAddress) {
        AuditLog entry = AuditLog.builder()
                .organizationId(TenantContext.getOrganizationId())
                .userId(TenantContext.getUserId())
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .details(details)
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(entry);
    }

    @Transactional
    public void log(Long orgId, Long userId, String action, String resourceType,
                    String resourceId, Map<String, Object> details) {
        AuditLog entry = AuditLog.builder()
                .organizationId(orgId)
                .userId(userId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .details(details)
                .build();
        auditLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findByOrganization(Long orgId, Pageable pageable) {
        return auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);
    }
}
