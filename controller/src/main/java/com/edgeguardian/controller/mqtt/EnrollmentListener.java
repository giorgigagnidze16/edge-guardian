package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.dto.AgentRegisterResponse;
import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.mqtt.payload.EnrollRequestPayload;
import com.edgeguardian.controller.service.BrokerCaProvider;
import com.edgeguardian.controller.service.CertificateAuthorityService;
import com.edgeguardian.controller.service.CertificateService;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.EnrollmentService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnrollmentListener {

    private static final String IDENTITY_CERT_NAME = "device-identity";

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final DeviceRegistry deviceRegistry;
    private final EnrollmentService enrollmentService;
    private final CertificateAuthorityService caService;
    private final CertificateService certificateService;
    private final BrokerCaProvider brokerCaProvider;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT not connected, enrollment subscription deferred");
            return;
        }
        String topic = topicRoot + "/device/+/enroll/request";
        try {
            mqttClient.subscribe(
                    new MqttSubscription[]{new MqttSubscription(topic, MqttTopics.QOS_RELIABLE)},
                    new IMqttMessageListener[]{this::onEnrollRequest});
            log.info("Subscribed to enrollment topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to enrollment topic", e);
        }
    }

    private void onEnrollRequest(String topic, MqttMessage message) {
        String deviceId = MqttTopics.extractDeviceId(topic);
        try {
            var request = objectMapper.readValue(message.getPayload(), EnrollRequestPayload.class);
            String resolvedId = request.deviceId() != null ? request.deviceId() : deviceId;

            validate(resolvedId, request);

            var result = enrollmentService.enrollDevice(
                    request.enrollmentToken(), resolvedId, request.hostname(),
                    request.architecture(), request.os(), request.agentVersion(), request.labels());

            Device device = result.device();
            IssuedCertificate identityCert = issueIdentityCertIfRequested(device, request);

            String trustBundle = buildTrustBundle(device.getOrganizationId());

            var response = new AgentRegisterResponse(
                    true,
                    "Device enrolled successfully",
                    buildManifestMap(device.getDeviceId()),
                    result.deviceToken(),
                    trustBundle,
                    identityCert != null ? identityCert.getCertPem() : null,
                    identityCert != null ? identityCert.getSerialNumber() : null
            );

            publish(resolvedId, response);
            log.info("Device {} enrolled to org {} (identityCert={})",
                    resolvedId, device.getOrganizationId(),
                    identityCert != null ? identityCert.getSerialNumber() : "none");

        } catch (Exception e) {
            log.error("Enrollment failed for topic {}: {}", topic, e.getMessage(), e);
            if (deviceId != null) {
                publish(deviceId, AgentRegisterResponse.error(e.getMessage()));
            }
        }
    }

    private void validate(String deviceId, EnrollRequestPayload request) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (request.enrollmentToken() == null || request.enrollmentToken().isBlank()) {
            throw new IllegalArgumentException("enrollmentToken is required");
        }
    }

    private String buildTrustBundle(Long orgId) {
        String orgPem = caService.getCaCertPem(orgId);
        String brokerPem = brokerCaProvider.getPem();
        if (brokerPem.isEmpty()) {
            return orgPem;
        }
        StringBuilder sb = new StringBuilder(orgPem);
        if (!orgPem.endsWith("\n")) sb.append('\n');
        sb.append(brokerPem);
        if (!brokerPem.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    // Treated as MANIFEST so compromise detection applies to re-enrollments.
    private IssuedCertificate issueIdentityCertIfRequested(Device device, EnrollRequestPayload request) {
        if (request.csrPem() == null || request.csrPem().isBlank()) {
            return null;
        }

        String commonName = request.commonName() != null && !request.commonName().isBlank()
                ? request.commonName()
                : device.getDeviceId();
        List<String> sans = request.sans() != null ? request.sans() : List.of();

        var result = certificateService.processRequest(
                device.getDeviceId(),
                device.getOrganizationId(),
                IDENTITY_CERT_NAME,
                commonName,
                sans,
                request.csrPem(),
                CertRequestType.MANIFEST,
                null);

        if (result.blocked()) {
            log.warn("Identity cert request blocked for device {} during enrollment (possible compromise)",
                    device.getDeviceId());
            return null;
        }
        return result.certificate();
    }

    private Map<String, Object> buildManifestMap(String deviceId) {
        return deviceRegistry.getManifest(deviceId)
                .map(EnrollmentListener::toManifestMap)
                .orElse(null);
    }

    private void publish(String deviceId, AgentRegisterResponse response) {
        String responseTopic = topicRoot + "/device/" + deviceId + "/enroll/response";
        try {
            var msg = new MqttMessage(objectMapper.writeValueAsBytes(response));
            msg.setQos(MqttTopics.QOS_RELIABLE);
            mqttClient.publish(responseTopic, msg);
        } catch (Exception e) {
            log.error("Failed to publish enrollment response to {}: {}", deviceId, e.getMessage());
        }
    }

    private static Map<String, Object> toManifestMap(DeviceManifestEntity entity) {
        var map = new LinkedHashMap<String, Object>();
        map.put("apiVersion", entity.getApiVersion());
        map.put("kind", entity.getKind());
        map.put("metadata", entity.getMetadata() != null ? entity.getMetadata() : Map.of());
        map.put("spec", entity.getSpec() != null ? entity.getSpec() : Map.of());
        map.put("version", entity.getVersion());
        return map;
    }

}
