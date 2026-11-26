import { API_BASE } from "../config/api-base";

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly status?: number,
    readonly retryable = false,
  ) {
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
    credentials: "include",
    headers,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as {
      code?: string;
      message?: string;
      retryable?: boolean;
    };
    if (response.status === 401 && !path.startsWith("/auth/")) {
      window.dispatchEvent(new Event("tmc-session-expired"));
    }
    throw new ApiRequestError(
      error.message ?? `Yêu cầu thất bại (${response.status}).`,
      error.code,
      response.status,
      error.retryable ?? response.status >= 500,
    );
  }
  return response;
}

export async function request<T>(path: string, init?: RequestInit, base?: string): Promise<T> {
  const response = await send(path, init, base);
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}

export async function download(path: string, init: RequestInit): Promise<Blob> {
  return (await send(path, init)).blob();
}
