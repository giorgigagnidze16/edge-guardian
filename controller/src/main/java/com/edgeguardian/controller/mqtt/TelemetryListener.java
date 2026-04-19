package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.mqtt.payload.TelemetryPayload;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * Subscribes to MQTT telemetry topics and updates device status in the database.
 * Topic pattern: {topicRoot}/device/+/telemetry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryListener {

    private final DeviceRegistry registry;
    private final ObjectMapper objectMapper;
    private final MqttSubscriptions subscriptions;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/telemetry",
                MqttTopics.QOS_BEST_EFFORT, this::onTelemetryMessage);
    }

    private void onTelemetryMessage(String topic, MqttMessage message) {
        try {
            var payload = objectMapper.readValue(message.getPayload(), TelemetryPayload.class);

            if (payload.deviceId() == null || payload.deviceId().isBlank()) {
                log.warn("Telemetry message missing deviceId, topic={}", topic);
                return;
            }
            if (payload.status() == null) {
                return;
            }

            registry.heartbeat(payload.deviceId(), payload.status().toEntity());

            log.debug("Telemetry processed for device {}: cpu={}%",
                    payload.deviceId(), payload.status().cpuUsagePercent());

        } catch (Exception e) {
            log.error("Failed to process telemetry message from topic {}: {}",
                    topic, e.getMessage());
        }
    }
}
