import { request } from "../../shared/http/client";
import type { VerifyPayload, VerifyResult } from "../model/types";

export const accessApi = {
  verify: (payload: VerifyPayload, stationId: string) =>
    request<VerifyResult>("/access/v1/verify", {
      method: "POST",
      headers: {
        "X-Station-Id": stationId,
        "X-Correlation-Id": crypto.randomUUID(),
        "Idempotency-Key": payload.eventId,
      },
      body: JSON.stringify(payload),
    }),
};
