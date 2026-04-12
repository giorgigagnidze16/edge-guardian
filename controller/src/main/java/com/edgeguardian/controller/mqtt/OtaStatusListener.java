package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.mqtt.payload.OtaStatusPayload;
import com.edgeguardian.controller.service.OTAService;
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
 * Handles OTA status reports from devices over MQTT.
 * Subscribe: {topicRoot}/device/+/ota/status
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaStatusListener {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final OTAService otaService;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, OTA status subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/ota/status";
        try {
            var subscription = new MqttSubscription(topic, MqttTopics.QOS_RELIABLE);
            IMqttMessageListener listener = this::onOtaStatus;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to OTA status topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to OTA status topic: {}", e.getMessage());
        }
    }

    private void onOtaStatus(String topic, MqttMessage message) {
        try {
            var payload = objectMapper.readValue(message.getPayload(), OtaStatusPayload.class);

            log.info("OTA status: deploymentId={}, deviceId={}, state={}, progress={}",
                    payload.deploymentId(), payload.deviceId(), payload.state(), payload.progress());

            otaService.updateDeviceOtaStatus(
                    payload.deploymentId(), payload.deviceId(),
                    payload.state(), payload.progress(), payload.errorMessage());

        } catch (Exception e) {
            log.error("Failed to process OTA status from topic {}: {}", topic, e.getMessage());
        }
    }

}
