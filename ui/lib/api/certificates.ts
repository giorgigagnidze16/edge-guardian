import { apiFetch } from "../api-client";

export interface CertificateRequest {
  id: number;
  deviceId: string;
  name: string;
  commonName: string;
  sans: string[];
  type: string;
  state: string;
  rejectReason: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

export interface Certificate {
  id: number;
  deviceId: string;
  name: string;
  commonName: string;
  serialNumber: string;
  status: string;
  notBefore: string;
  notAfter: string;
  revoked: boolean;
  revokeReason: string | null;
  createdAt: string;
}

export async function listCertificateRequests(token: string): Promise<CertificateRequest[]> {
  return apiFetch<CertificateRequest[]>("/api/v1/certificates/requests", { token });
}

export async function listCertificates(token: string): Promise<Certificate[]> {
  return apiFetch<Certificate[]>("/api/v1/certificates", { token });
}

export async function approveCertRequest(token: string, requestId: number): Promise<Certificate> {
  return apiFetch<Certificate>(
    `/api/v1/certificates/requests/${requestId}/approve`,
    { method: "POST", token },
  );
}

export async function rejectCertRequest(token: string, requestId: number, reason?: string): Promise<void> {
  return apiFetch(
    `/api/v1/certificates/requests/${requestId}/reject`,
    { method: "POST", token, body: JSON.stringify({ reason: reason ?? "" }) },
  );
}

export async function revokeCertificate(token: string, certId: number): Promise<void> {
  return apiFetch(`/api/v1/certificates/${certId}/revoke`, { method: "POST", token });
}

export async function getCaCert(token: string): Promise<string> {
  const response = await fetch(
    `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8443"}/api/v1/certificates/ca`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!response.ok) throw new Error("Failed to download CA cert");
  return response.text();
}
