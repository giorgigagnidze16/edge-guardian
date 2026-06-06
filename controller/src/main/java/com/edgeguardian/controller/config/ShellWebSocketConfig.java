package com.edgeguardian.controller.config;

import com.edgeguardian.controller.api.ApiPaths;
import com.edgeguardian.controller.mqtt.ShellWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the interactive shell WebSocket endpoint. The handshake is
 * ticket-authenticated (see {@link ShellWebSocketHandler}); the path is
 * {@code permitAll} in {@link SecurityConfig} so the browser can connect
 * without a bearer token on the WebSocket URL.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(ShellProperties.class)
@RequiredArgsConstructor
public class ShellWebSocketConfig implements WebSocketConfigurer {

    private final ShellWebSocketHandler shellWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(shellWebSocketHandler, ApiPaths.SHELL_WS)
                .setAllowedOriginPatterns("*"); // ticket-authenticated; tighten per deployment
    }
}
