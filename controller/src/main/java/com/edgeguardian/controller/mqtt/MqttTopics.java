package com.edgeguardian.controller.mqtt;

/**
 * Shared MQTT topic utilities and constants.
 * Topic format: {topicRoot}/device/{deviceId}/{suffix}
 */
public final class MqttTopics {

    /**
     * At-least-once - for commands, enrollment, certs (must not be lost).
     */
    public static final int QOS_RELIABLE = 1;

    /**
     * Fire-and-forget - for telemetry, heartbeats, logs (periodic, loss is fine).
     */
    public static final int QOS_BEST_EFFORT = 0;

    private MqttTopics() {
    }

    /**
     * Extracts the deviceId from a topic like "edgeguardian/device/rpi-001/enroll/request".
     */
    public static String extractDeviceId(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[2] : null;
    }
}
