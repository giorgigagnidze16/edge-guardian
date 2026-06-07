import { API_BASE_URL, apiFetch } from "../api-client";
import { endpoints } from "./endpoints";

export interface ShellSessionResponse {
  sessionId: string;
  wsTicket: string;
}

/**
 * Authorizes an interactive shell session and mints a one-time WebSocket
 * ticket. The terminal I/O itself flows over the WebSocket (see
 * {@link shellWebSocketUrl}), not this REST call.
 */
export async function createShellSession(
  token: string,
  deviceId: string,
  size: { rows: number; cols: number },
): Promise<ShellSessionResponse> {
  return apiFetch<ShellSessionResponse>(endpoints.devices.shell.sessions(deviceId), {
    method: "POST",
    token,
    body: JSON.stringify({ rows: size.rows, cols: size.cols }),
  });
}

/**
 * Builds the absolute ws(s):// URL for the shell WebSocket, carrying the
 * one-time ticket as a query param. The ticket — not the JWT — authenticates
 * the handshake, so it must never be logged or reused.
 */
export function shellWebSocketUrl(ticket: string): string {
  const base =
    API_BASE_URL || (typeof window !== "undefined" ? window.location.origin : "");
  const wsBase = base.replace(/^http/, "ws");
  return `${wsBase}${endpoints.devices.shell.ws()}?ticket=${encodeURIComponent(ticket)}`;
}
