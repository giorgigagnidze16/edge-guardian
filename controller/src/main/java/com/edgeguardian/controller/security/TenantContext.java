package com.edgeguardian.controller.security;

/**
 * ThreadLocal-based tenant context. Set by TenantInterceptor from the JWT claims.
 * Virtual threads are pinned to their carrier during request handling, so ThreadLocal is safe.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_ORG_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setOrganizationId(Long orgId) {
        CURRENT_ORG_ID.set(orgId);
    }

    public static Long getOrganizationId() {
        return CURRENT_ORG_ID.get();
    }

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void clear() {
        CURRENT_ORG_ID.remove();
        CURRENT_USER_ID.remove();
    }
}
