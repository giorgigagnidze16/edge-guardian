package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.DeviceDto;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.LogService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * REST API for device management.
 * Provides CRUD operations over registered devices.
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceRegistry registry;
    private final LogService logService;

    public DeviceController(DeviceRegistry registry, LogService logService) {
        this.registry = registry;
        this.logService = logService;
    }

    @GetMapping
    public List<DeviceDto> listDevices() {
        return registry.findAll().stream()
                .map(DeviceDto::from)
                .toList();
    }

    @GetMapping("/{deviceId}")
    public DeviceDto getDevice(@PathVariable String deviceId) {
        return registry.findById(deviceId)
                .map(DeviceDto::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found: " + deviceId));
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
}
