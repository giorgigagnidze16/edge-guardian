package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.mqtt.payload.TelemetryPayload;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Subscribes to MQTT telemetry topics and updates device status in the database.
 * Topic pattern: {topicRoot}/device/+/telemetry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryListener {

    private final MqttClient mqttClient;
    private final DeviceRegistry registry;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, telemetry subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/telemetry";
        try {
            MqttSubscription subscription = new MqttSubscription(topic, MqttTopics.QOS_BEST_EFFORT);
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
