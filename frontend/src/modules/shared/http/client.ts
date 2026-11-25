import { API_BASE } from "../config/api-base";

async function send(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (init?.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { code?: string; message?: string };
    throw new Error(error.message ?? error.code ?? `API error ${response.status}`);
  }
  return response;
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return (await send(path, init)).json() as Promise<T>;
}

export async function download(path: string, init: RequestInit): Promise<Blob> {
  return (await send(path, init)).blob();
}
