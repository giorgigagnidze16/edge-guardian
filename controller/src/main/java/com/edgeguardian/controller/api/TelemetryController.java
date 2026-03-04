package com.edgeguardian.controller.api;

import com.edgeguardian.controller.dto.TelemetryDataPoint;
import com.edgeguardian.controller.service.TelemetryService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * REST API for device telemetry time-series data.
 * Falls under the existing /api/v1/** authenticated pattern in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/devices/{deviceId}/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping
    public List<TelemetryDataPoint> getRawTelemetry(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end) {
        if (start == null) start = Instant.now().minus(1, ChronoUnit.HOURS);
        if (end == null) end = Instant.now();
        return telemetryService.getRawTelemetry(deviceId, start, end);
    }

    @GetMapping("/hourly")
    public List<TelemetryDataPoint> getHourlyTelemetry(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end) {
        if (start == null) start = Instant.now().minus(24, ChronoUnit.HOURS);
        if (end == null) end = Instant.now();
        return telemetryService.getHourlyTelemetry(deviceId, start, end);
    }
}
