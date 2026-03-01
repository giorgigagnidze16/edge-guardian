package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Marks devices as OFFLINE after 5 minutes without heartbeat.
 */
@Component
public class DeviceHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthScheduler.class);
    private static final long OFFLINE_THRESHOLD_MINUTES = 5;

    private final DeviceRepository deviceRepository;

    public DeviceHealthScheduler(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Scheduled(fixedRate = 60_000) // Run every minute
    @Transactional
    public void markOfflineDevices() {
        Instant threshold = Instant.now().minus(OFFLINE_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
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
            log.info("Marked {} device(s) as OFFLINE (no heartbeat for {} min)", count, OFFLINE_THRESHOLD_MINUTES);
        }
    }
}
