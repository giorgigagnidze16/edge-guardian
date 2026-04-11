package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.service.CertificateAuthorityService;
import com.edgeguardian.controller.service.CertificateService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CertRequestListener {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final CertificateService certificateService;
    private final CertificateAuthorityService caService;
    private final DeviceRepository deviceRepository;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, cert request subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/cert/request";
        try {
            var subscription = new MqttSubscription(topic, MqttTopics.QOS_RELIABLE);
            IMqttMessageListener listener = this::onCertRequest;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to cert request topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to cert request topic: {}", e.getMessage());
        }
    }

    private void onCertRequest(String topic, MqttMessage message) {
        String deviceId = extractDeviceId(topic);
        try {
            var request = objectMapper.readValue(message.getPayload(), CertRequestPayload.class);
            String resolvedDeviceId = request.deviceId() != null ? request.deviceId() : deviceId;

            if (resolvedDeviceId == null || resolvedDeviceId.isBlank()) {
                publishResponse(deviceId, request.name(), false, "deviceId is required", null, null);
                return;
            }
            if (request.csrPem() == null || request.csrPem().isBlank()) {
                publishResponse(resolvedDeviceId, request.name(), false, "csrPem is required", null, null);
                return;
            }

            Long orgId = deviceRepository.findByDeviceId(resolvedDeviceId)
                    .map(d -> d.getOrganizationId())
                    .orElse(null);

            if (orgId == null) {
                publishResponse(resolvedDeviceId, request.name(), false,
                        "Device not registered", null, null);
                return;
            }

            CertRequestType type = parseType(request.type());
            var result = certificateService.processRequest(
                    resolvedDeviceId, orgId, request.name(), request.commonName(),
                    request.sans() != null ? request.sans() : List.of(),
                    request.csrPem(), type, request.currentSerial());

            if (result.blocked()) {
                publishResponse(resolvedDeviceId, request.name(), false,
                        "Blocked: device already has valid certificate. Possible compromise detected.",
                        null, null);
                return;
            }

            IssuedCertificate cert = result.certificate();
            if (cert != null) {
                publishResponse(resolvedDeviceId, request.name(), true,
                        "Certificate issued", cert.getCertPem(), caService.getCaCertPem(orgId));
            }
            // If cert is null and not blocked, request is pending manual approval.
            // Response will be sent when admin approves (via CertificateController).

        } catch (Exception e) {
            log.error("Failed to process cert request from topic {}: {}", topic, e.getMessage(), e);
            if (deviceId != null) {
                publishResponse(deviceId, null, false, "Internal error: " + e.getMessage(), null, null);
            }
        }
    }

    /**
     * Publish a signed certificate to a device. Called by CertificateController after admin approval.
     */
    public void publishCertResponse(String deviceId, Long orgId, String name, String certPem) {
        publishResponse(deviceId, name, true, "Certificate issued", certPem, caService.getCaCertPem(orgId));
    }

    private void publishResponse(String deviceId, String name, boolean accepted,
                                 String message, String certPem, String caCertPem) {
        if (deviceId == null) return;
        try {
            String responseTopic = topicRoot + "/device/" + deviceId + "/cert/response";
            var response = new CertResponsePayload(name, accepted, message, certPem, caCertPem);
            byte[] payload = objectMapper.writeValueAsBytes(response);
            var msg = new MqttMessage(payload);
            msg.setQos(MqttTopics.QOS_RELIABLE);
            msg.setRetained(false);
            mqttClient.publish(responseTopic, msg);
            log.debug("Published cert response to device {}: accepted={}", deviceId, accepted);
        } catch (Exception e) {
            log.error("Failed to publish cert response to device {}: {}", deviceId, e.getMessage());
        }
    }

    private String extractDeviceId(String topic) {
        return MqttTopics.extractDeviceId(topic);
    }

    private CertRequestType parseType(String type) {
        if (type == null) return CertRequestType.INITIAL;
        return switch (type.toLowerCase()) {
            case "renewal" -> CertRequestType.RENEWAL;
            case "manifest" -> CertRequestType.MANIFEST;
            default -> CertRequestType.INITIAL;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CertRequestPayload(
            String deviceId,
            String name,
            String commonName,
            List<String> sans,
            String csrPem,
            String type,
            String currentSerial
    ) {}

    record CertResponsePayload(
            String name,
            boolean accepted,
            String message,
            String certPem,
            String caCertPem
    ) {}
}
