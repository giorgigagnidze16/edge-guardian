package com.edgeguardian.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Forwards device logs to Loki and proxies LogQL queries for the dashboard.
 */
@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.loki.push-url:http://localhost:3100/loki/api/v1/push}")
    private String lokiPushUrl;

    @Value("${edgeguardian.controller.loki.query-url:http://localhost:3100/loki/api/v1/query_range}")
    private String lokiQueryUrl;

    public LogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Pushes log entries from a device to Loki.
     * Converts MQTT log entries to Loki push API format.
     */
    public void pushToLoki(String deviceId, JsonNode entries) {
        try {
            // Build Loki push payload
            ObjectNode payload = objectMapper.createObjectNode();
            ArrayNode streams = payload.putArray("streams");

            ObjectNode stream = streams.addObject();
            ObjectNode streamLabels = stream.putObject("stream");
            streamLabels.put("job", "edgeguardian");
            streamLabels.put("device_id", deviceId);

            ArrayNode values = stream.putArray("values");

            for (JsonNode entry : entries) {
                String timestamp = entry.path("timestamp").asText("");
                String level = entry.path("level").asText("INFO");
                String message = entry.path("message").asText("");
                String source = entry.path("source").asText("agent");

                // Loki expects [timestamp_ns, log_line]
                long nanos;
                try {
                    nanos = Instant.parse(timestamp).toEpochMilli() * 1_000_000;
                } catch (Exception e) {
                    nanos = Instant.now().toEpochMilli() * 1_000_000;
                }

                ArrayNode value = values.addArray();
                value.add(String.valueOf(nanos));
                value.add(String.format("level=%s source=%s %s", level, source, message));
            }

            String body = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(lokiPushUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.warn("Loki push failed for device {}: HTTP {} - {}",
                        deviceId, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to push logs to Loki for device {}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Queries Loki for device logs using LogQL.
     *
     * @param deviceId the device to query logs for
     * @param start    start time (ISO 8601)
     * @param end      end time (ISO 8601)
     * @param limit    max number of log lines
     * @param level    optional log level filter
     * @param search   optional text search
     * @return raw Loki query response as JSON
     */
    public JsonNode queryLogs(String deviceId, String start, String end,
                              int limit, String level, String search) {
        try {
            // Build LogQL query
            StringBuilder query = new StringBuilder();
            query.append("{job=\"edgeguardian\",device_id=\"").append(deviceId).append("\"}");

            if (level != null && !level.isEmpty()) {
                query.append(" |= \"level=").append(level).append("\"");
            }
            if (search != null && !search.isEmpty()) {
                query.append(" |= \"").append(search.replace("\"", "\\\"")).append("\"");
            }

            String url = lokiQueryUrl
                    + "?query=" + java.net.URLEncoder.encode(query.toString(), "UTF-8")
                    + "&start=" + java.net.URLEncoder.encode(start, "UTF-8")
                    + "&end=" + java.net.URLEncoder.encode(end, "UTF-8")
                    + "&limit=" + limit
                    + "&direction=backward";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("Failed to query Loki for device {}: {}", deviceId, e.getMessage());
            return objectMapper.createObjectNode().put("error", e.getMessage());
        }
    }
}
