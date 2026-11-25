export type VerifyPayload = {
  eventId: string;
  siteId: string;
  direction: "ENTRY" | "EXIT";
  scannedAt: string;
  token: string;
  deviceId: string;
};

export type VerifyResult = {
  eventId: string;
  decision: string;
  reasonCode: string | null;
  command: string;
  passage: string;
  evidence: string | null;
  kioskMessage: string;
  correlationId: string;
  steps: string[];
};
