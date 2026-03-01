package com.edgeguardian.controller.model;

import java.util.Map;

/**
 * Sealed interface for device commands dispatched via MQTT.
 */
public sealed interface DeviceCommand {

    record OtaUpdate(
            long deploymentId,
            String artifactName,
            String artifactVersion,
            String downloadUrl,
            String sha256,
            String ed25519Sig
    ) implements DeviceCommand {}

    record Restart(
            String reason
    ) implements DeviceCommand {}

    record Exec(
            String command,
            Map<String, String> env,
            int timeoutSeconds
    ) implements DeviceCommand {}

    record VpnConfigure(
            String publicKey,
            String endpoint,
            String allowedIps
    ) implements DeviceCommand {}
}
