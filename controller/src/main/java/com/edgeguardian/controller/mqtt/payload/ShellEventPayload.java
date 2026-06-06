package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Session lifecycle event published by the agent on {@code .../shell/event}.
 * {@code type} is one of started | exited | error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShellEventPayload(String sessionId, String type, Integer exitCode, String msg) {}
