import { request } from "../../shared/http/client";
import type { VerifyPayload, VerifyResult } from "../model/types";

export const accessApi = {
  verify: (payload: VerifyPayload, stationId: string, signal?: AbortSignal) =>
    request<VerifyResult>("/access/v1/verify", {
      method: "POST",
      headers: {
        "X-Station-Id": stationId,
        "X-Correlation-Id": payload.eventId,
        "Idempotency-Key": payload.eventId,
      },
      body: JSON.stringify(payload),
      signal: signal ?? null,
    }),
  result: (id: string, stationId: string, signal?: AbortSignal) =>
    request<VerifyResult>(`/access/v1/scans/${encodeURIComponent(id)}`, {
      headers: { "X-Station-Id": stationId },
      signal: signal ?? null,
    }),
};
