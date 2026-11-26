import { afterEach, describe, expect, it, vi } from "vitest";
import { accessApi } from "../modules/access/api/access-api";
import { attendanceApi } from "../modules/attendance/api/attendance-api";
import { qrApi } from "../modules/userqr/api/qr-api";

describe("TMC frontend API contracts", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends the station and idempotency headers for access verification", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ decision: "ALLOW" }) });
    vi.stubGlobal("fetch", fetchMock);
    const payload = { eventId: "evt-1", siteId: "SITE-1", direction: "ENTRY" as const, scannedAt: "2026-01-01T00:00:00Z", token: "signed", deviceId: "device-1" };
    await accessApi.verify(payload, "GATE-1");
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/access/v1/verify");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get("X-Station-Id")).toBe("GATE-1");
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe("evt-1");
    expect(JSON.parse(String(init.body))).toEqual(payload);
  });

  it("uses the backend QR issue and revoke routes", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ token: "signed", status: "REVOKED" }) });
    vi.stubGlobal("fetch", fetchMock);
    await qrApi.issue("cred-1", "employee", "registered-proof");
    await qrApi.revoke("cred-1", "hr");
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/qr/v1/tokens");
    const issue = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(issue.headers).get("Authorization")).toBe("Bearer employee");
    expect(new Headers(issue.headers).get("X-Device-Proof")).toBe("registered-proof");
    expect(JSON.parse(String(issue.body))).toEqual({ credentialId: "cred-1" });
    expect(fetchMock.mock.calls[1]?.[0]).toContain("/qr/v1/credentials/cred-1/revoke");
  });

  it("loads attendance from the backend record route", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    await attendanceApi.list("test-token");
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/attendance/v1/records");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization")).toBe("Bearer test-token");
  });

  it("downloads a monthly report using the HR bearer token", async () => {
    const csv = new Blob(["event_id"]);
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => csv });
    vi.stubGlobal("fetch", fetchMock);
    expect(await attendanceApi.exportCsv("hr", "2026-11")).toBe(csv);
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/reports/v1/exports?month=2026-11");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization")).toBe("Bearer hr");
  });
});
