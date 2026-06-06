package com.edgeguardian.controller.dto;

/**
 * Optional body for opening a shell session - the terminal's initial size.
 * Defaults are applied when omitted.
 *
 * @param rows initial terminal rows (cells)
 * @param cols initial terminal columns (cells)
 */
public record CreateShellSessionRequest(Integer rows, Integer cols) {}
