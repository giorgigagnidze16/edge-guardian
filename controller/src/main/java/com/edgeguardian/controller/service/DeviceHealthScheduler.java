package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Marks devices as OFFLINE after a configurable period without heartbeat.
 */
@Slf4j
@Component
public class DeviceHealthScheduler {

    private final DeviceRepository deviceRepository;
    private final long offlineThresholdMinutes;

    public DeviceHealthScheduler(
            DeviceRepository deviceRepository,
            @Value("${edgeguardian.controller.device.offline-threshold-minutes:5}") long offlineThresholdMinutes) {
        this.deviceRepository = deviceRepository;
        this.offlineThresholdMinutes = offlineThresholdMinutes;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void markOfflineDevices() {
        Instant threshold = Instant.now().minus(offlineThresholdMinutes, ChronoUnit.MINUTES);
        List<Device> onlineDevices = deviceRepository.findByState(Device.DeviceState.ONLINE);

        int count = 0;
        for (Device device : onlineDevices) {
            if (device.getLastHeartbeat() != null && device.getLastHeartbeat().isBefore(threshold)) {
                device.setState(Device.DeviceState.OFFLINE);
                deviceRepository.save(device);
                count++;
            }
        }

        if (count > 0) {
            log.info("Marked {} device(s) as OFFLINE (no heartbeat for {} min)", count, offlineThresholdMinutes);
        }
    }
}
