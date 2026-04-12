package com.edgeguardian.controller.dto;

import java.util.Map;

/**
 * Registration response sent to the agent.
 *
 * <p>Fields relevant to mTLS bootstrap:
 * <ul>
 *   <li>{@code caCertPem} — the organization's CA certificate (trust anchor for agent server-auth).</li>
 *   <li>{@code identityCertPem} — leaf certificate signed for the device's CSR (present only
 *       when the agent submitted a CSR in the enroll request). This cert is what the agent
 *       subsequently presents on the mTLS listener.</li>
 *   <li>{@code identityCertSerial} — hex-encoded serial of the same leaf, used by the agent to
 *       submit renewal requests later.</li>
 * </ul>
 */
public record AgentRegisterResponse(
        boolean accepted,
        String message,
        Map<String, Object> initialManifest,
        String deviceToken,
        String caCertPem,
        String identityCertPem,
        String identityCertSerial
) {
    public static AgentRegisterResponse error(String message) {
        return new AgentRegisterResponse(false, message, null, null, null, null, null);
    }
}
