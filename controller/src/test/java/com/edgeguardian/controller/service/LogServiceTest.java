package com.edgeguardian.controller.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogServiceTest {

    @Test
    void pushEndpointAppendsLokiPath() {
        assertEquals("http://loki:3100/loki/api/v1/push",
                LogService.pushEndpoint("http://loki:3100"));
    }

    @Test
    void queryEndpointAppendsLokiPath() {
        assertEquals("http://loki:3100/loki/api/v1/query_range",
                LogService.queryEndpoint("http://loki:3100"));
    }

    @Test
    void trimsTrailingSlashFromBase() {
        assertEquals("http://loki:3100/loki/api/v1/push",
                LogService.pushEndpoint("http://loki:3100/"));
        assertEquals("http://loki:3100/loki/api/v1/query_range",
                LogService.queryEndpoint("http://loki:3100/"));
    }

    @Test
    void buildQueryUriPercentEncodesLogqlBraces() {
        URI uri = LogService.buildQueryUri("http://loki:3100",
                "{job=\"edgeguardian\",device_id=\"d1\"}", "100", "200", 500);
        String s = uri.toString();
        assertTrue(s.startsWith("http://loki:3100/loki/api/v1/query_range?"), s);
        assertFalse(s.contains("{"), "raw '{' must not appear in the URI: " + s);
        assertTrue(s.contains("query=%7Bjob"), "LogQL must be percent-encoded: " + s);
    }
}
