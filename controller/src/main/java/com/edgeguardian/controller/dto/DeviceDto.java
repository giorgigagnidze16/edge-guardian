package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceStatus;
import java.time.Instant;
import java.util.Map;

/**
 * DTO for REST API device responses.
 */
public record DeviceDto(
    String deviceId,
    String hostname,
    String architecture,
    String os,
    String agentVersion,
    Map<String, String> labels,
    String state,
    Instant registeredAt,
    Instant lastHeartbeat,
    DeviceStatus status
) {
    public static DeviceDto from(Device device) {
        return from(device, null);
    }

    public static DeviceDto from(Device device, DeviceStatus status) {
        return new DeviceDto(
            device.getDeviceId(),
            device.getHostname(),
            device.getArchitecture(),
            device.getOs(),
            device.getAgentVersion(),
            device.getLabels(),
            device.getState().name(),
            device.getRegisteredAt(),
            device.getLastHeartbeat(),
            status
        );
    }
}
