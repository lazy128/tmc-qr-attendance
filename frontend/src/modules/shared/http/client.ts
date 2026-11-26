import { API_BASE } from "../config/api-base";

export class ApiRequestError extends Error {
  constructor(message: string, readonly code?: string) {
    super(message);
  }
}

async function send(path: string, init?: RequestInit, base = API_BASE): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (init?.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(`${base}${path}`, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { code?: string; message?: string };
    throw new ApiRequestError(error.message ?? error.code ?? `API error ${response.status}`, error.code);
  }
  return response;
}

export async function request<T>(path: string, init?: RequestInit, base?: string): Promise<T> {
  return (await send(path, init, base)).json() as Promise<T>;
}

export async function download(path: string, init: RequestInit): Promise<Blob> {
  return (await send(path, init)).blob();
}
