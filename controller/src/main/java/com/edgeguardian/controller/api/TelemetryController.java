package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.TelemetryDataPoint;
import com.edgeguardian.controller.security.TenantPrincipal;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;
    private final DeviceRegistry deviceRegistry;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<TelemetryDataPoint> getRawTelemetry(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @AuthenticationPrincipal TenantPrincipal principal) {
        assertOwnership(deviceId, principal);
        if (start == null) start = Instant.now().minus(1, ChronoUnit.HOURS);
        if (end == null) end = Instant.now();
        return telemetryService.getRawTelemetry(deviceId, start, end);
    }

    @GetMapping("/hourly")
    @PreAuthorize("@orgSecurity.hasMinRole(authentication, 'VIEWER')")
    public List<TelemetryDataPoint> getHourlyTelemetry(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @AuthenticationPrincipal TenantPrincipal principal) {
        assertOwnership(deviceId, principal);
        if (start == null) start = Instant.now().minus(24, ChronoUnit.HOURS);
        if (end == null) end = Instant.now();
        return telemetryService.getHourlyTelemetry(deviceId, start, end);
    }

    private void assertOwnership(String deviceId, TenantPrincipal principal) {
        deviceRegistry.findByIdForOrganization(deviceId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found: " + deviceId));
    }
}
