package com.edgeguardian.controller.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps live shell sessions to their browser WebSocket so the MQTT bridge can
 * push output and close terminals when the device-side shell ends. Centralizes
 * thread-safe sends (WebSocketSession is not safe for concurrent writes).
 */
@Slf4j
@Component
public class ShellWebSocketRegistry {

    private static final int MAX_REASON_LEN = 123; // CloseStatus reason byte cap

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(String sessionId, WebSocketSession ws) {
        sessions.put(sessionId, ws);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Send output bytes to a browser terminal; serialized per-socket. */
    public void sendBinary(WebSocketSession ws, byte[] data) {
        try {
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new BinaryMessage(data));
                }
            }
        } catch (IOException e) {
            log.debug("Failed to send shell output to WebSocket: {}", e.getMessage());
        }
    }

    /** Close the browser terminal for a session (device shell exited). */
    public void close(String sessionId, String reason) {
        WebSocketSession ws = sessions.remove(sessionId);
        if (ws == null) {
            return;
        }
        try {
            if (ws.isOpen()) {
                ws.close(CloseStatus.NORMAL.withReason(truncate(reason)));
            }
        } catch (IOException e) {
            log.debug("Failed to close shell WebSocket {}: {}", sessionId, e.getMessage());
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON_LEN ? reason : reason.substring(0, MAX_REASON_LEN);
    }
}
