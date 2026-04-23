import { absoluteUrl, apiFetch } from "../api-client";
import { endpoints } from "./endpoints";

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
  return apiFetch<MeResponse>(endpoints.me(), { token });
}

export async function getOrganization(token: string): Promise<Organization> {
  return apiFetch<Organization>(endpoints.organization.get(), { token });
}

export async function updateOrganization(
  token: string,
  data: { name?: string; description?: string },
): Promise<Organization> {
  return apiFetch<Organization>(endpoints.organization.update(), {
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
  return apiFetch<OrgMember[]>(endpoints.organization.members.list(), { token });
}

export async function addMember(token: string, data: { email: string; role: string }): Promise<void> {
  return apiFetch(endpoints.organization.members.create(), {
    method: "POST", token, body: JSON.stringify(data),
  });
}

export async function removeMember(token: string, memberId: number): Promise<void> {
  return apiFetch(endpoints.organization.members.byId(memberId), { method: "DELETE", token });
}

export async function updateMemberRole(
  token: string,
  memberId: number,
  role: string,
): Promise<OrgMember> {
  return apiFetch<OrgMember>(endpoints.organization.members.byId(memberId), {
    method: "PATCH", token, body: JSON.stringify({ role }),
  });
}

export async function listEnrollmentTokens(token: string): Promise<EnrollmentToken[]> {
  return apiFetch<EnrollmentToken[]>(endpoints.enrollmentTokens.list(), { token });
}

export async function createEnrollmentToken(
  token: string,
  data: { description: string; maxUses: number },
): Promise<EnrollmentToken> {
  return apiFetch<EnrollmentToken>(endpoints.enrollmentTokens.create(), {
    method: "POST", token, body: JSON.stringify(data),
  });
}

export async function deleteEnrollmentToken(token: string, tokenId: number): Promise<void> {
  return apiFetch(endpoints.enrollmentTokens.byId(tokenId), { method: "DELETE", token });
}

export function buildInstallerUrl(
  os: "windows" | "linux" | "darwin",
  arch: "amd64" | "arm64",
  tokenSecret: string,
): string {
  const params = new URLSearchParams({ os, arch, token: tokenSecret });
  return absoluteUrl(`${endpoints.agent.installer()}?${params.toString()}`);
}

export async function listApiKeys(token: string): Promise<ApiKeyEntry[]> {
  return apiFetch<ApiKeyEntry[]>(endpoints.apiKeys.list(), { token });
}

export async function createApiKey(token: string, data: { name: string }): Promise<{ id: number; rawKey: string }> {
  return apiFetch(endpoints.apiKeys.create(), { method: "POST", token, body: JSON.stringify(data) });
}

export async function deleteApiKey(token: string, keyId: number): Promise<void> {
  return apiFetch(endpoints.apiKeys.byId(keyId), { method: "DELETE", token });
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
    `${endpoints.organization.auditLog()}${qs ? `?${qs}` : ""}`, { token },
  );
}
