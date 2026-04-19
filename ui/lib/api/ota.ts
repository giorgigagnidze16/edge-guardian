import { absoluteUrl, apiFetch } from "../api-client";
import { endpoints } from "./endpoints";

export interface OtaArtifact {
  id: number;
  name: string;
  version: string;
  architecture: string;
  size: number;
  sha256: string;
  createdAt: string;
}

export interface OtaDeployment {
  id: number;
  artifactId: number;
  strategy: string;
  state: string;
  labelSelector: Record<string, string>;
  createdAt: string;
  totalDevices?: number;
  completedDevices?: number;
}

export interface DeploymentDeviceStatus {
  deviceId: string;
  hostname: string;
  state: string;
  progress: number;
  errorMessage: string | null;
  updatedAt: string;
}

export async function listArtifacts(token: string): Promise<OtaArtifact[]> {
  return apiFetch<OtaArtifact[]>(endpoints.ota.artifacts.list(), { token });
}

export async function listDeployments(token: string): Promise<OtaDeployment[]> {
  return apiFetch<OtaDeployment[]>(endpoints.ota.deployments.list(), { token });
}

export async function uploadArtifact(
  token: string,
  data: { name: string; version: string; architecture: string; file: File },
): Promise<OtaArtifact> {
  const formData = new FormData();
  formData.append("file", data.file);
  formData.append("name", data.name);
  formData.append("version", data.version);
  formData.append("architecture", data.architecture);

  const response = await fetch(absoluteUrl(endpoints.ota.artifacts.create()), {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: formData,
  });

  if (!response.ok) {
    const body = await response.text();
    let message = response.statusText || `HTTP ${response.status}`;
    try {
      const json = JSON.parse(body);
      if (json.message) message = json.message;
    } catch {
      if (body) message = body;
    }
    throw new Error(message);
  }

  return response.json();
}

export async function createDeployment(
  token: string,
  data: { artifactId: number; strategy: string; labelSelector: Record<string, string> },
): Promise<OtaDeployment> {
  return apiFetch<OtaDeployment>(endpoints.ota.deployments.create(), {
    method: "POST", token, body: JSON.stringify(data),
  });
}

export async function getDeployment(token: string, deploymentId: number): Promise<OtaDeployment> {
  return apiFetch<OtaDeployment>(endpoints.ota.deployments.byId(deploymentId), { token });
}

export async function getDeploymentDevices(token: string, deploymentId: number): Promise<DeploymentDeviceStatus[]> {
  return apiFetch<DeploymentDeviceStatus[]>(
    endpoints.ota.deployments.devices(deploymentId),
    { token },
  );
}

export async function deleteArtifact(token: string, artifactId: number): Promise<void> {
  return apiFetch(endpoints.ota.artifacts.byId(artifactId), { method: "DELETE", token });
}
