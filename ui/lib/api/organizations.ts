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

export async function createOrganization(
  token: string,
  data: { name: string; slug: string; description?: string },
): Promise<Organization> {
  return apiFetch<Organization>("/api/v1/organizations", {
    method: "POST",
    token,
    body: JSON.stringify(data),
  });
}

export async function getOrganization(
  token: string,
  orgId: number,
): Promise<Organization> {
  return apiFetch<Organization>(`/api/v1/organizations/${orgId}`, { token });
}

export interface OrgMember {
  userId: number;
  email: string;
  displayName: string;
  role: string;
  joinedAt: string;
}

export interface EnrollmentToken {
  id: number;
  token: string;
  description: string;
  maxUses: number;
  currentUses: number;
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

export async function listMembers(
  token: string,
  orgId: number,
): Promise<OrgMember[]> {
  return apiFetch<OrgMember[]>(
    `/api/v1/organizations/${orgId}/members`,
    { token },
  );
}

export async function addMember(
  token: string,
  orgId: number,
  data: { email: string; role: string },
): Promise<void> {
  return apiFetch(`/api/v1/organizations/${orgId}/members`, {
    method: "POST",
    token,
    body: JSON.stringify(data),
  });
}

export async function listEnrollmentTokens(
  token: string,
  orgId: number,
): Promise<EnrollmentToken[]> {
  return apiFetch<EnrollmentToken[]>(
    `/api/v1/organizations/${orgId}/enrollment-tokens`,
    { token },
  );
}

export async function createEnrollmentToken(
  token: string,
  orgId: number,
  data: { description: string; maxUses: number },
): Promise<EnrollmentToken> {
  return apiFetch<EnrollmentToken>(
    `/api/v1/organizations/${orgId}/enrollment-tokens`,
    { method: "POST", token, body: JSON.stringify(data) },
  );
}

export async function listApiKeys(
  token: string,
  orgId: number,
): Promise<ApiKeyEntry[]> {
  return apiFetch<ApiKeyEntry[]>(
    `/api/v1/organizations/${orgId}/api-keys`,
    { token },
  );
}

export async function createApiKey(
  token: string,
  orgId: number,
  data: { name: string },
): Promise<{ id: number; rawKey: string }> {
  return apiFetch(`/api/v1/organizations/${orgId}/api-keys`, {
    method: "POST",
    token,
    body: JSON.stringify(data),
  });
}
