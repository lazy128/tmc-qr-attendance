import { request } from "../../shared/http/client";

export const qrApi = {
  issue: (credentialId: string, bearer: string, deviceProof: string, signal?: AbortSignal) =>
    request<{ token: string; expiresAt: string }>("/qr/v1/tokens", {
      method: "POST",
      headers: { Authorization: `Bearer ${bearer}`, "X-Device-Proof": deviceProof },
      body: JSON.stringify({ credentialId }),
      signal: signal ?? null,
    }),
  revoke: (credentialId: string, bearer: string) =>
    request<{ status: string }>(`/qr/v1/credentials/${encodeURIComponent(credentialId)}/revoke`, {
      method: "POST",
      headers: { Authorization: `Bearer ${bearer}` },
    }),
  enroll: (credentialId: string, bearer: string) =>
    request<{ deviceProof: string }>(
      `/qr/v1/credentials/${encodeURIComponent(credentialId)}/devices`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${bearer}` },
      },
    ),
};
