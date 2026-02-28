package com.edgeguardian.controller.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes commands to devices via MQTT.
 *
 * Topic: {topicRoot}/device/{deviceId}/command
 *
 * Payload matches the agent's model.CommandMessage JSON:
 * {
 *   "command": {
 *     "id": "...",
 *     "type": "...",
 *     "params": { ... },
 *     "createdAt": "..."
 *   }
 * }
 */
@Component
public class CommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    public CommandPublisher(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a command to a specific device.
     *
     * @param deviceId  the target device
     * @param type      command type (e.g. "ota_update", "restart", "exec")
     * @param params    command parameters
     * @throws MqttException if publishing fails
     */
    public void publishCommand(String deviceId, String type, Map<String, String> params)
            throws MqttException {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, cannot publish command to device {}", deviceId);
            return;
        }

        String topic = topicRoot + "/device/" + deviceId + "/command";

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("id", UUID.randomUUID().toString());
        command.put("type", type);
        command.put("params", params != null ? params : Map.of());
        command.put("createdAt", Instant.now().toString());

        Map<String, Object> envelope = Map.of("command", command);

        try {
            byte[] payload = objectMapper.writeValueAsBytes(envelope);

            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            message.setRetained(false);

            mqttClient.publish(topic, message);
            log.info("Command published to device {}: type={}, id={}",
                    deviceId, type, command.get("id"));

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize command for device {}: {}", deviceId, e.getMessage());
        }
    }
}
