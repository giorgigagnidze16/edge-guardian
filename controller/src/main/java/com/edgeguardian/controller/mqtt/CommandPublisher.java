package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.mqtt.payload.CommandEnvelope;
import com.edgeguardian.controller.mqtt.payload.CommandPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes commands and messages to devices via MQTT.
 * Topic: {topicRoot}/device/{deviceId}/command
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandPublisher {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    /**
     * Publish a command to a specific device.
     */
    public void publishCommand(String deviceId, String type, Map<String, String> params)
            throws MqttException {
        var command = new CommandPayload(
                UUID.randomUUID().toString(),
                type,
                params != null ? params : Map.of(),
                Instant.now()
        );

        publishToDevice(deviceId, "command", new CommandEnvelope(command), false);
        log.info("Command published to device {}: type={}, id={}", deviceId, type, command.id());
    }

    /**
     * Publish a JSON payload to a device-specific topic.
     */
    private void publishToDevice(String deviceId, String topicSuffix, Object payload, boolean retained)
            throws MqttException {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, cannot publish to device {} topic {}", deviceId, topicSuffix);
            return;
        }

        String topic = topicRoot + "/device/" + deviceId + "/" + topicSuffix;
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            var message = new MqttMessage(bytes);
            message.setQos(MqttTopics.QOS_RELIABLE);
            message.setRetained(retained);
            mqttClient.publish(topic, message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for device {} topic {}: {}", deviceId, topicSuffix, e.getMessage());
        }
    }

}
