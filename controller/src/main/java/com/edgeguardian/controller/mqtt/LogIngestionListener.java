package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.service.LogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Subscribes to MQTT log topics from devices and forwards log entries to Loki.
 *
 * Topic pattern: {topicRoot}/device/+/logs
 *
 * Payload is a JSON array of log entries:
 * [
 *   { "timestamp": "2024-01-01T00:00:00Z", "level": "INFO", "message": "...", "source": "agent" },
 *   ...
 * ]
 */
@Component
public class LogIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionListener.class);

    private final MqttClient mqttClient;
    private final LogService logService;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    public LogIngestionListener(MqttClient mqttClient,
                                LogService logService,
                                ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.logService = logService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, log ingestion subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/logs";
        try {
            MqttSubscription subscription = new MqttSubscription(topic, 1);
            IMqttMessageListener listener = this::onLogMessage;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to log ingestion topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to log topic {}: {}", topic, e.getMessage());
        }
    }

    private void onLogMessage(String topic, MqttMessage message) {
        try {
            // Extract device ID from topic: edgeguardian/device/{deviceId}/logs
            String[] parts = topic.split("/");
            if (parts.length < 4) {
                log.warn("Invalid log topic format: {}", topic);
                return;
            }
            String deviceId = parts[parts.length - 2];

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
