export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

export const absoluteUrl = (path: string) => `${API_BASE_URL}${path}`;

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type FetchOptions = RequestInit & { token?: string };

async function rawFetch(path: string, options: FetchOptions = {}): Promise<Response> {
  const { token, ...fetchOptions } = options;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(fetchOptions.headers as Record<string, string>),
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(absoluteUrl(path), { ...fetchOptions, headers });
  if (!response.ok) {
    const body = await response.text();
    throw new ApiError(response.status, body || response.statusText);
  }
  return response;
}

export async function apiFetch<T>(path: string, options: FetchOptions = {}): Promise<T> {
  const response = await rawFetch(path, options);
  if (response.status === 204) return undefined as T;
  return response.json();
}

export async function apiFetchText(path: string, options: FetchOptions = {}): Promise<string> {
  const response = await rawFetch(path, options);
  return response.text();
}
