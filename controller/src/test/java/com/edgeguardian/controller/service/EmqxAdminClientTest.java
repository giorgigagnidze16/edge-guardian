package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.EmqxProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmqxAdminClientTest {

    @Test
    void kickoutUsesHttp1WithoutH2cUpgradeHeaders() throws Exception {
        Map<String, String> captured = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/clients/dev-1/kickout", exchange -> {
            exchange.getRequestHeaders()
                    .forEach((k, v) -> captured.put(k.toLowerCase(), String.join(",", v)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var props = new EmqxProperties(
                    "http://127.0.0.1:" + port + "/api/v5", "admin", "secret", 3000);

            new EmqxAdminClient(props).kickout("dev-1");

            assertTrue(captured.containsKey("authorization"),
                    "kickout request should reach the EMQX admin endpoint");
            assertFalse(captured.containsKey("upgrade"),
                    "kickout must not send an h2c Upgrade header (EMQX rejects it)");
            assertFalse(captured.containsKey("http2-settings"),
                    "kickout must not send HTTP2-Settings");
        } finally {
            server.stop(0);
        }
    }
}
