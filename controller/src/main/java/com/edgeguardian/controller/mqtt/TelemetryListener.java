package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.service.DeviceRegistry;
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

import java.time.Instant;

/**
 * Subscribes to MQTT telemetry topics and updates device status in the database.
 *
 * Topic pattern: {topicRoot}/device/+/telemetry
 * The '+' wildcard matches any device ID.
 *
 * Payload is the agent's model.TelemetryMessage JSON:
 * {
 *   "deviceId": "...",
 *   "timestamp": "...",
 *   "status": { cpuUsagePercent, memoryUsedBytes, ... }
 * }
 */
@Component
public class TelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetryListener.class);

    private final MqttClient mqttClient;
    private final DeviceRegistry registry;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    public TelemetryListener(MqttClient mqttClient,
                             DeviceRegistry registry,
                             ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, telemetry subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/telemetry";
        try {
            MqttSubscription subscription = new MqttSubscription(topic, 1);
            IMqttMessageListener listener = this::onTelemetryMessage;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to telemetry topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to telemetry topic {}: {}", topic, e.getMessage());
        }
    }

    private void onTelemetryMessage(String topic, MqttMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());

            String deviceId = root.path("deviceId").asText(null);
            if (deviceId == null || deviceId.isBlank()) {
                log.warn("Telemetry message missing deviceId, topic={}", topic);
                return;
            }

            JsonNode statusNode = root.path("status");
            if (statusNode.isMissingNode()) {
                return;
            }

            DeviceStatus status = new DeviceStatus();
            status.setCpuUsagePercent(statusNode.path("cpuUsagePercent").asDouble(0));
            status.setMemoryUsedBytes(statusNode.path("memoryUsedBytes").asLong(0));
            status.setMemoryTotalBytes(statusNode.path("memoryTotalBytes").asLong(0));
            status.setDiskUsedBytes(statusNode.path("diskUsedBytes").asLong(0));
            status.setDiskTotalBytes(statusNode.path("diskTotalBytes").asLong(0));
            status.setTemperatureCelsius(statusNode.path("temperatureCelsius").asDouble(0));
            status.setUptimeSeconds(statusNode.path("uptimeSeconds").asLong(0));

            String lastReconcile = statusNode.path("lastReconcile").asText(null);
            if (lastReconcile != null && !lastReconcile.isEmpty()) {
                try {
                    status.setLastReconcile(Instant.parse(lastReconcile));
                } catch (Exception e) {
                    log.debug("Could not parse lastReconcile from telemetry: {}", lastReconcile);
                }
            }

            status.setReconcileStatus(statusNode.path("reconcileStatus").asText("unknown"));

            registry.heartbeat(deviceId, status);

            log.debug("Telemetry processed for device {}: cpu={}%",
                    deviceId, status.getCpuUsagePercent());

        } catch (Exception e) {
            log.error("Failed to process telemetry message from topic {}: {}",
                    topic, e.getMessage());
        }
    }
}
