package com.edgeguardian.controller.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
