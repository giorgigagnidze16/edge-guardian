package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.DeviceDto;
import com.edgeguardian.controller.model.CommandExecution;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceCommand;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.mqtt.CommandPublisher;
import com.edgeguardian.controller.repository.CommandExecutionRepository;
import com.edgeguardian.controller.repository.DeviceCommandRepository;
import com.edgeguardian.controller.security.TenantContext;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.LogService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;

/**
 * REST API for device management.
 * Provides CRUD operations over registered devices.
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceRegistry registry;
    private final LogService logService;
    private final DeviceCommandRepository commandRepository;
    private final CommandExecutionRepository executionRepository;
    private final CommandPublisher commandPublisher;

    @GetMapping
    public List<DeviceDto> listDevices() {
        List<Device> devices = registry.findAll();
        Map<String, DeviceStatus> statusMap = registry.getLatestStatusForAllDevices();
        return devices.stream()
            .map(d -> DeviceDto.from(d, statusMap.get(d.getDeviceId())))
            .toList();
    }

    @GetMapping("/{deviceId}")
    public DeviceDto getDevice(@PathVariable String deviceId) {
        Device device = registry.findById(deviceId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Device not found: " + deviceId));
        DeviceStatus status = registry.getLatestStatus(deviceId).orElse(null);
        return DeviceDto.from(device, status);
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@PathVariable String deviceId) {
        if (!registry.remove(deviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + deviceId);
        }
    }

    @GetMapping("/count")
    public long countDevices() {
        return registry.count();
    }

    @GetMapping("/{deviceId}/logs")
    public JsonNode getDeviceLogs(
        @PathVariable String deviceId,
        @RequestParam(defaultValue = "") String start,
        @RequestParam(defaultValue = "") String end,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) String search) {
        if (start.isEmpty()) {
            start = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        }
        if (end.isEmpty()) {
            end = Instant.now().toString();
        }
        return logService.queryLogs(deviceId, start, end, limit, level, search);
    }

    // --- Commands ---

    record CreateCommandRequest(
            String type,
            Map<String, String> params,
            Map<String, Object> script,
            Map<String, Object> hooks,
            int timeoutSeconds
    ) {}

    @PostMapping("/{deviceId}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceCommand sendCommand(@PathVariable String deviceId,
                                     @RequestBody CreateCommandRequest request) {
        Device device = registry.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));

        String commandId = UUID.randomUUID().toString();

        DeviceCommand command = commandRepository.save(DeviceCommand.builder()
                .commandId(commandId)
                .deviceId(deviceId)
                .organizationId(device.getOrganizationId())
                .type(request.type())
                .params(request.params() != null ? request.params() : Map.of())
                .script(request.script())
                .hooks(request.hooks())
                .timeoutSeconds(request.timeoutSeconds())
                .createdBy(TenantContext.getUserId())
                .build());

        try {
            commandPublisher.publishCommand(deviceId, request.type(),
                    request.params() != null ? request.params() : Map.of());
            command.setState("sent");
            command.setSentAt(Instant.now());
            commandRepository.save(command);
        } catch (MqttException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to publish command via MQTT: " + e.getMessage());
        }

        return command;
    }

    @GetMapping("/{deviceId}/commands")
    public List<DeviceCommand> listCommands(@PathVariable String deviceId) {
        return commandRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @GetMapping("/{deviceId}/commands/{commandId}/results")
    public List<CommandExecution> getCommandResults(@PathVariable String deviceId,
                                                     @PathVariable String commandId) {
        return executionRepository.findByCommandIdOrderByReceivedAtAsc(commandId);
    }
}
