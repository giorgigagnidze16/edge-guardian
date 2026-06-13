package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.CreateCommandRequest;
import com.edgeguardian.controller.dto.DeviceDto;
import com.edgeguardian.controller.dto.ManifestUpdateRequest;
import com.edgeguardian.controller.model.CommandExecution;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceCommand;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.mqtt.CommandPublisher;
import com.edgeguardian.controller.repository.CommandExecutionRepository;
import com.edgeguardian.controller.repository.DeviceCommandRepository;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.DeviceLifecycleService;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.LogService;
import com.edgeguardian.controller.service.ManifestYaml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.DEVICES_BASE)
@RequiredArgsConstructor
public class DeviceController {

    private final LogService logService;
    private final DeviceRegistry registry;
    private final ObjectMapper objectMapper;
    private final CommandPublisher commandPublisher;
    private final DeviceCommandRepository commandRepository;
    private final CommandExecutionRepository executionRepository;
    private final DeviceLifecycleService deviceLifecycleService;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<DeviceDto> listDevices(@AuthenticationPrincipal TenantPrincipal principal) {
        List<Device> devices = registry.findByOrganizationId(principal.organizationId());
        Map<String, DeviceStatus> statusMap =
                registry.getLatestStatusForOrganization(principal.organizationId());
        return devices.stream()
                .map(d -> DeviceDto.from(d, statusMap.get(d.getDeviceId())))
                .toList();
    }

    @GetMapping("/{deviceId}")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public DeviceDto getDevice(@PathVariable String deviceId,
                               @AuthenticationPrincipal TenantPrincipal principal) {
        Device device = loadForTenant(deviceId, principal);
        DeviceStatus status = registry.getLatestStatus(deviceId).orElse(null);
        return DeviceDto.from(device, status);
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ADMIN')")
    public void removeDevice(@PathVariable String deviceId,
                             @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        deviceLifecycleService.deleteDevice(deviceId, principal.userId());
    }

    @GetMapping("/count")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public long countDevices(@AuthenticationPrincipal TenantPrincipal principal) {
        return registry.countByOrganizationId(principal.organizationId());
    }

    @GetMapping("/{deviceId}/logs")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public JsonNode getDeviceLogs(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "") String start,
            @RequestParam(defaultValue = "") String end,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        if (start.isEmpty()) {
            start = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        }
        if (end.isEmpty()) {
            end = Instant.now().toString();
        }
        return logService.queryLogs(deviceId, start, end, limit, level, search);
    }

    @GetMapping("/{deviceId}/manifest")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public JsonNode getManifest(@PathVariable String deviceId,
                                @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        String yaml = registry.getManifest(deviceId)
                .map(ManifestYaml::toYaml)
                .orElseGet(() -> ManifestYaml.skeleton(deviceId));
        return objectMapper.valueToTree(yaml);
    }

    @PutMapping("/{deviceId}/manifest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    public void updateManifest(@PathVariable String deviceId,
                               @RequestBody ManifestUpdateRequest request,
                               @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        ManifestYaml.Parsed parsed;
        try {
            parsed = ManifestYaml.parse(request.content());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        registry.saveManifest(deviceId, parsed.metadata(), parsed.spec());
    }

    @PostMapping("/{deviceId}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'OPERATOR')")
    public DeviceCommand sendCommand(@PathVariable String deviceId,
                                     @RequestBody CreateCommandRequest request,
                                     @AuthenticationPrincipal TenantPrincipal principal) {
        Device device = loadForTenant(deviceId, principal);

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
                .createdBy(principal.userId())
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
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<DeviceCommand> listCommands(@PathVariable String deviceId,
                                            @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        return commandRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @GetMapping("/{deviceId}/commands/{commandId}/results")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<CommandExecution> getCommandResults(@PathVariable String deviceId,
                                                    @PathVariable String commandId,
                                                    @AuthenticationPrincipal TenantPrincipal principal) {
        loadForTenant(deviceId, principal);
        return executionRepository.findByCommandIdOrderByReceivedAtAsc(commandId);
    }

    private Device loadForTenant(String deviceId, TenantPrincipal principal) {
        return registry.findByIdForOrganization(deviceId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found: " + deviceId));
    }
}
