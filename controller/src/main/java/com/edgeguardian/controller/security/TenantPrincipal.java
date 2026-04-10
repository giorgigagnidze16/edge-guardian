package com.edgeguardian.controller.security;

public record TenantPrincipal(Long organizationId, Long userId, String identity) {
}
