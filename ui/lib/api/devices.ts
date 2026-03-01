import { apiFetch } from "../api-client";

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
  return apiFetch<Device[]>("/api/v1/devices", { token });
}

export async function getDevice(
  token: string,
  deviceId: string,
): Promise<Device> {
  return apiFetch<Device>(`/api/v1/devices/${deviceId}`, { token });
}

export async function deleteDevice(
  token: string,
  deviceId: string,
): Promise<void> {
  return apiFetch(`/api/v1/devices/${deviceId}`, {
    method: "DELETE",
    token,
  });
}

export async function getDeviceCount(token: string): Promise<number> {
  return apiFetch<number>("/api/v1/devices/count", { token });
}

export async function getDeviceManifest(
  token: string,
  deviceId: string,
): Promise<unknown> {
  return apiFetch(`/api/v1/devices/${deviceId}/manifest`, { token });
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
  return apiFetch(`/api/v1/devices/${deviceId}/logs?${q.toString()}`, { token });
}

export async function updateDeviceManifest(
  token: string,
  deviceId: string,
  yamlContent: string,
): Promise<void> {
  return apiFetch(`/api/v1/devices/${deviceId}/manifest`, {
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
  return apiFetch(`/api/v1/devices/${deviceId}/labels`, {
    method: "PUT",
    token,
    body: JSON.stringify(labels),
  });
}
