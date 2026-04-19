package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.mqtt.payload.OtaStatusPayload;
import com.edgeguardian.controller.service.OTAService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * Handles OTA status reports from devices over MQTT.
 * Subscribe: {topicRoot}/device/+/ota/status
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaStatusListener {

    private final ObjectMapper objectMapper;

    private final OTAService otaService;
    private final MqttSubscriptions subscriptions;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/ota/status",
                MqttTopics.QOS_RELIABLE, this::onOtaStatus);
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
