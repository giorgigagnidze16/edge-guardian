package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.service.LogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * Subscribes to MQTT log topics from devices and forwards log entries to Loki.
 * Topic pattern: {topicRoot}/device/+/logs
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogIngestionListener {

    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final MqttSubscriptions subscriptions;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/logs",
                MqttTopics.QOS_BEST_EFFORT, this::onLogMessage);
    }

    private void onLogMessage(String topic, MqttMessage message) {
        try {
            String deviceId = MqttTopics.extractDeviceId(topic);
            if (deviceId == null || deviceId.isEmpty()) {
                log.warn("Invalid log topic format: {}", topic);
                return;
            }

            JsonNode entries = objectMapper.readTree(message.getPayload());
            if (entries.isArray()) {
                logService.pushToLoki(deviceId, entries);
            }

            log.debug("Forwarded {} log entries from device {}", entries.size(), deviceId);
        } catch (Exception e) {
            log.error("Failed to process log message from topic {}: {}", topic, e.getMessage());
        }
    }
}
