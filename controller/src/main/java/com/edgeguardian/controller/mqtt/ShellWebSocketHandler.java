package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.service.ShellSession;
import com.edgeguardian.controller.service.ShellSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Bridges a browser terminal WebSocket to a device shell. The handshake is
 * authenticated by the one-time ticket (query param) rather than the JWT;
 * binary frames are keystrokes, text frames are control (resize) JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShellWebSocketHandler extends AbstractWebSocketHandler {

    private static final String ATTR_SESSION = "shellSession";

    private final ShellSessionService sessionService;
    private final ShellMqttBridge bridge;
    private final ShellWebSocketRegistry registry;

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        String ticket = ticketFromQuery(ws);
        if (ticket == null) {
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("missing ticket"));
            return;
        }

        ShellSession session;
        try {
            session = sessionService.activate(ticket, data -> registry.sendBinary(ws, data));
        } catch (ResponseStatusException e) {
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("invalid ticket"));
            return;
        }

        ws.getAttributes().put(ATTR_SESSION, session);
        registry.add(session.sessionId(), ws);
        bridge.publishOpen(session.deviceId(), session.sessionId(), session.rows(), session.cols());
        log.info("Shell WebSocket opened: session={} device={}", session.sessionId(), session.deviceId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        ShellSession session = session(ws);
        if (session != null) {
            bridge.publishInput(session.deviceId(), session.sessionId(), toBytes(message.getPayload()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        ShellSession session = session(ws);
        if (session != null) {
            bridge.publishControl(session.deviceId(), session.sessionId(),
                    message.getPayload().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        ShellSession session = session(ws);
        if (session != null) {
            registry.remove(session.sessionId());
            sessionService.close(session.sessionId(), "ws_closed");
            bridge.publishClose(session.deviceId(), session.sessionId());
            log.info("Shell WebSocket closed: session={}", session.sessionId());
        }
    }

    private static ShellSession session(WebSocketSession ws) {
        return (ShellSession) ws.getAttributes().get(ATTR_SESSION);
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private static String ticketFromQuery(WebSocketSession ws) {
        if (ws.getUri() == null || ws.getUri().getQuery() == null) {
            return null;
        }
        for (String pair : ws.getUri().getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "ticket".equals(pair.substring(0, eq))) {
                String value = pair.substring(eq + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
