import { apiFetch } from "../api-client";
import { endpoints } from "./endpoints";

export interface DeviceStatus {
  cpuUsagePercent: number;
  memoryUsedBytes: number;
  memoryTotalBytes: number;
  diskUsedBytes: number;
  diskTotalBytes: number;
  temperatureCelsius: number;
  uptimeSeconds: number;
  lastReconcile: string | null;
  reconcileStatus: string;
}

export interface Device {
  deviceId: string;
  hostname: string;
  architecture: string;
  os: string;
  agentVersion: string;
  labels: Record<string, string>;
  state: string;
  registeredAt: string;
  lastHeartbeat: string | null;
  status: DeviceStatus;
}

export async function listDevices(token: string): Promise<Device[]> {
  return apiFetch<Device[]>(endpoints.devices.list(), { token });
}

export async function getDevice(token: string, deviceId: string): Promise<Device> {
  return apiFetch<Device>(endpoints.devices.byId(deviceId), { token });
}

export async function deleteDevice(token: string, deviceId: string): Promise<void> {
  return apiFetch(endpoints.devices.byId(deviceId), { method: "DELETE", token });
}

export async function getDeviceCount(token: string): Promise<number> {
  return apiFetch<number>(endpoints.devices.count(), { token });
}

export async function getDeviceManifest(token: string, deviceId: string): Promise<unknown> {
  return apiFetch(endpoints.devices.manifest(deviceId), { token });
}

export async function getDeviceLogs(
  token: string,
  deviceId: string,
  params?: { start?: string; end?: string; limit?: number; level?: string; search?: string },
): Promise<unknown> {
  const q = new URLSearchParams();
  if (params?.start) q.set("start", params.start);
  if (params?.end) q.set("end", params.end);
  if (params?.limit) q.set("limit", String(params.limit));
  if (params?.level) q.set("level", params.level);
  if (params?.search) q.set("search", params.search);
  return apiFetch(`${endpoints.devices.logs(deviceId)}?${q.toString()}`, { token });
}

export async function updateDeviceManifest(
  token: string,
  deviceId: string,
  yamlContent: string,
): Promise<void> {
  return apiFetch(endpoints.devices.manifest(deviceId), {
    method: "PUT",
    token,
    body: JSON.stringify({ content: yamlContent }),
  });
}

export async function updateDeviceLabels(
  token: string,
  deviceId: string,
  labels: Record<string, string>,
): Promise<void> {
  return apiFetch(endpoints.devices.labels(deviceId), {
    method: "PUT",
    token,
    body: JSON.stringify(labels),
  });
}

export interface TelemetryDataPoint {
  time: string;
  cpuUsagePercent: number;
  memoryUsedBytes: number;
  memoryTotalBytes: number;
  diskUsedBytes: number;
  diskTotalBytes: number;
  temperatureCelsius: number;
  uptimeSeconds: number;
  reconcileStatus: string;
}

export type TelemetryBucket = "raw" | "hourly";

export async function getDeviceTelemetry(
  token: string,
  deviceId: string,
  params: { start: string; end: string; bucket?: TelemetryBucket },
): Promise<TelemetryDataPoint[]> {
  const path = params.bucket === "hourly"
    ? endpoints.devices.telemetryHourly(deviceId)
    : endpoints.devices.telemetry(deviceId);
  const q = new URLSearchParams({ start: params.start, end: params.end });
  return apiFetch<TelemetryDataPoint[]>(`${path}?${q.toString()}`, { token });
}
