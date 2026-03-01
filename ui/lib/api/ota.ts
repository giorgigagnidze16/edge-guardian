import { apiFetch } from "../api-client";

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
}

export async function listArtifacts(
  token: string,
  orgId: number,
): Promise<OtaArtifact[]> {
  return apiFetch<OtaArtifact[]>(
    `/api/v1/organizations/${orgId}/ota/artifacts`,
    { token },
  );
}

export async function listDeployments(
  token: string,
  orgId: number,
): Promise<OtaDeployment[]> {
  return apiFetch<OtaDeployment[]>(
    `/api/v1/organizations/${orgId}/ota/deployments`,
    { token },
  );
}

export async function createArtifact(
  token: string,
  orgId: number,
  data: { name: string; version: string; architecture: string },
): Promise<OtaArtifact> {
  return apiFetch<OtaArtifact>(
    `/api/v1/organizations/${orgId}/ota/artifacts`,
    { method: "POST", token, body: JSON.stringify(data) },
  );
}

export async function createDeployment(
  token: string,
  orgId: number,
  data: { artifactId: number; strategy: string; labelSelector: Record<string, string> },
): Promise<OtaDeployment> {
  return apiFetch<OtaDeployment>(
    `/api/v1/organizations/${orgId}/ota/deployments`,
    { method: "POST", token, body: JSON.stringify(data) },
  );
}
