package com.edgeguardian.controller.service;

/**
 * Receives shell output bytes destined for a connected browser terminal. The
 * WebSocket layer registers a sink so {@link ShellSessionService} stays free of
 * transport types and remains unit-testable.
 */
@FunctionalInterface
public interface ShellOutputSink {
    void send(byte[] data);
}
