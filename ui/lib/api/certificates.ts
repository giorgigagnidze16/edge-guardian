import { apiFetch, apiFetchText } from "../api-client";
import { endpoints } from "./endpoints";

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
  return apiFetch<CertificateRequest[]>(endpoints.certificates.requests.list(), { token });
}

export async function listCertificates(token: string): Promise<Certificate[]> {
  return apiFetch<Certificate[]>(endpoints.certificates.list(), { token });
}

export async function approveCertRequest(token: string, requestId: number): Promise<Certificate> {
  return apiFetch<Certificate>(
    endpoints.certificates.requests.approve(requestId),
    { method: "POST", token },
  );
}

export async function rejectCertRequest(token: string, requestId: number, reason?: string): Promise<void> {
  return apiFetch(
    endpoints.certificates.requests.reject(requestId),
    { method: "POST", token, body: JSON.stringify({ reason: reason ?? "" }) },
  );
}

export async function revokeCertificate(token: string, certId: number): Promise<void> {
  return apiFetch(endpoints.certificates.revoke(certId), { method: "POST", token });
}

export async function getCaCert(token: string): Promise<string> {
  return apiFetchText(endpoints.certificates.ca(), { token });
}
