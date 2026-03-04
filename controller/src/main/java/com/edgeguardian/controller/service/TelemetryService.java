package com.edgeguardian.controller.service;

import com.edgeguardian.controller.dto.TelemetryDataPoint;
import com.edgeguardian.controller.repository.DeviceTelemetryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TelemetryService {

    private final DeviceTelemetryRepository telemetryRepository;

    public TelemetryService(DeviceTelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    @Transactional(readOnly = true)
    public List<TelemetryDataPoint> getRawTelemetry(String deviceId, Instant start, Instant end) {
        return telemetryRepository.findByDeviceIdAndTimeRange(deviceId, start, end)
                .stream()
                .map(TelemetryDataPoint::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TelemetryDataPoint> getHourlyTelemetry(String deviceId, Instant start, Instant end) {
        return telemetryRepository.findHourlyByDeviceIdAndTimeRange(deviceId, start, end)
                .stream()
                .map(TelemetryDataPoint::from)
                .toList();
    }
}
