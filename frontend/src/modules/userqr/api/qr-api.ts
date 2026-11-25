import { request } from "../../shared/http/client";

export const qrApi = {
  issue: (credentialId: string, deviceHash: string) =>
    request<{ token: string }>("/qr/v1/tokens", {
      method: "POST",
      body: JSON.stringify({ credentialId, deviceHash }),
    }),
  revoke: (credentialId: string) =>
    request<{ status: string }>(`/qr/v1/credentials/${credentialId}/revoke`, { method: "POST" }),
};
