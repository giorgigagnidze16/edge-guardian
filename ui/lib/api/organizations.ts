import { apiFetch } from "../api-client";

export interface Organization {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  createdAt: string;
}

export interface MeResponse {
  user: {
    id: number;
    email: string;
    displayName: string;
    avatarUrl: string | null;
    createdAt: string;
  };
  organizations: {
    id: number;
    name: string;
    slug: string;
    role: string;
  }[];
}

export async function getMe(token: string): Promise<MeResponse> {
  return apiFetch<MeResponse>("/api/v1/me", { token });
}

export async function getOrganization(token: string): Promise<Organization> {
  return apiFetch<Organization>("/api/v1/organization", { token });
}

export async function updateOrganization(
  token: string,
  data: { name?: string; description?: string },
): Promise<Organization> {
  return apiFetch<Organization>("/api/v1/organization", {
    method: "PUT", token, body: JSON.stringify(data),
  });
}

export interface OrgMember {
  id: number;
  userId: number;
  email: string | null;
  displayName: string | null;
  avatarUrl: string | null;
  role: string;
  joinedAt: string;
}

export interface EnrollmentToken {
  id: number;
  token: string;
  name: string;
  maxUses: number;
  useCount: number;
  expiresAt: string | null;
  createdAt: string;
}

export interface ApiKeyEntry {
  id: number;
  name: string;
  prefix: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
}

export async function listMembers(token: string): Promise<OrgMember[]> {
  return apiFetch<OrgMember[]>("/api/v1/organization/members", { token });
}

export async function addMember(token: string, data: { email: string; role: string }): Promise<void> {
  return apiFetch("/api/v1/organization/members", {
    method: "POST", token, body: JSON.stringify(data),
  });
}

export async function removeMember(token: string, userId: number): Promise<void> {
  return apiFetch(`/api/v1/organization/members/${userId}`, { method: "DELETE", token });
}

export async function listEnrollmentTokens(token: string): Promise<EnrollmentToken[]> {
  return apiFetch<EnrollmentToken[]>("/api/v1/enrollment-tokens", { token });
}

export async function createEnrollmentToken(
  token: string,
  data: { description: string; maxUses: number },
): Promise<EnrollmentToken> {
  return apiFetch<EnrollmentToken>("/api/v1/enrollment-tokens", {
    method: "POST", token, body: JSON.stringify(data),
  });
}

export async function deleteEnrollmentToken(token: string, tokenId: number): Promise<void> {
  return apiFetch(`/api/v1/enrollment-tokens/${tokenId}`, { method: "DELETE", token });
}

export async function listApiKeys(token: string): Promise<ApiKeyEntry[]> {
  return apiFetch<ApiKeyEntry[]>("/api/v1/api-keys", { token });
}

export async function createApiKey(token: string, data: { name: string }): Promise<{ id: number; rawKey: string }> {
  return apiFetch("/api/v1/api-keys", { method: "POST", token, body: JSON.stringify(data) });
}

export async function deleteApiKey(token: string, keyId: number): Promise<void> {
  return apiFetch(`/api/v1/api-keys/${keyId}`, { method: "DELETE", token });
}

export interface AuditLogEntry {
  id: number;
  userId: number;
  userEmail: string;
  action: string;
  resourceType: string;
  resourceId: string;
  details: Record<string, unknown> | null;
  createdAt: string;
}

export async function listAuditLog(
  token: string,
  params?: { page?: number; size?: number; action?: string; resourceType?: string },
): Promise<AuditLogEntry[]> {
  const searchParams = new URLSearchParams();
  if (params?.page !== undefined) searchParams.set("page", String(params.page));
  if (params?.size !== undefined) searchParams.set("size", String(params.size));
  if (params?.action) searchParams.set("action", params.action);
  if (params?.resourceType) searchParams.set("resourceType", params.resourceType);
  const qs = searchParams.toString();
  return apiFetch<AuditLogEntry[]>(
    `/api/v1/organization/audit-log${qs ? `?${qs}` : ""}`, { token },
  );
}
