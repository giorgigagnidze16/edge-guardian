package com.edgeguardian.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LogService {

    private final RestClient restClient;

    @Value("${edgeguardian.controller.loki.push-url:http://localhost:3100/loki/api/v1/push}")
    private String lokiPushUrl;

    @Value("${edgeguardian.controller.loki.query-url:http://localhost:3100/loki/api/v1/query_range}")
    private String lokiQueryUrl;

    public LogService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void pushToLoki(String deviceId, JsonNode entries) {
        try {
            List<List<String>> values = new ArrayList<>();
            for (JsonNode entry : entries) {
                String timestamp = entry.path("timestamp").asText("");
                String level = entry.path("level").asText("INFO");
                String message = entry.path("message").asText("");
                String source = entry.path("source").asText("agent");

                long nanos;
                try {
                    nanos = Instant.parse(timestamp).toEpochMilli() * 1_000_000;
                } catch (Exception e) {
                    nanos = Instant.now().toEpochMilli() * 1_000_000;
                }

                values.add(List.of(
                        String.valueOf(nanos),
                        "level=%s source=%s %s".formatted(level, source, message)
                ));
            }

            var payload = Map.of("streams", List.of(Map.of(
                    "stream", Map.of("job", "edgeguardian", "device_id", deviceId),
                    "values", values
            )));

            restClient.post()
                    .uri(lokiPushUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to push logs to Loki for device {}: {}", deviceId, e.getMessage());
        }
    }

    public JsonNode queryLogs(String deviceId, String start, String end,
                              int limit, String level, String search) {
        try {
            StringBuilder query = new StringBuilder();
            query.append("{job=\"edgeguardian\",device_id=\"").append(deviceId).append("\"}");

            if (level != null && !level.isEmpty()) {
                query.append(" |= \"level=").append(level).append("\"");
            }
            if (search != null && !search.isEmpty()) {
                query.append(" |= \"").append(search.replace("\"", "\\\"")).append("\"");
            }

            String uri = UriComponentsBuilder.fromUriString(lokiQueryUrl)
                    .queryParam("query", query.toString())
                    .queryParam("start", start)
                    .queryParam("end", end)
                    .queryParam("limit", limit)
                    .queryParam("direction", "backward")
                    .build()
                    .toUriString();

            return restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.error("Failed to query Loki for device {}: {}", deviceId, e.getMessage());
            return null;
        }
    }
}
