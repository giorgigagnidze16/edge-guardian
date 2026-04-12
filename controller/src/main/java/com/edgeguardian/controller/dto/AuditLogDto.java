package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.AuditLog;

import java.util.Map;

public record AuditLogDto(Long id, Long userId, String userEmail, String action,
                          String resourceType, String resourceId,
                          Map<String, Object> details, java.time.Instant createdAt) {
    public static AuditLogDto from(AuditLog entry, String email) {
        return new AuditLogDto(entry.getId(), entry.getUserId(), email,
                entry.getAction(), entry.getResourceType(), entry.getResourceId(),
                entry.getDetails(), entry.getCreatedAt());
    }
}
