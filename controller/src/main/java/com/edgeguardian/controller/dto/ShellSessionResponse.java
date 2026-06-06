package com.edgeguardian.controller.dto;

/**
 * Response to opening a shell session: the session id and the one-time ticket
 * the browser presents on the WebSocket handshake.
 */
public record ShellSessionResponse(String sessionId, String wsTicket) {}
