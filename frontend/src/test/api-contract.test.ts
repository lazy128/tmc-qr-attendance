import { afterEach, describe, expect, it, vi } from "vitest";
import { accessApi } from "../modules/access/api/access-api";
import { workflow } from "../modules/shared/workflow";

describe("TMC frontend API contracts", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends the station and idempotency headers for access verification", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ decision: "ALLOW" }) });
    vi.stubGlobal("fetch", fetchMock);
    const payload = {
      eventId: "evt-1",
      siteId: "SITE-1",
      direction: "ENTRY" as const,
      scannedAt: "2026-01-01T00:00:00Z",
      token: "signed",
      deviceId: "device-1",
    };
    await accessApi.verify(payload, "GATE-1");
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/access/v1/verify");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get("X-Station-Id")).toBe("GATE-1");
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe("evt-1");
    expect(JSON.parse(String(init.body))).toEqual(payload);
  });

  it("requests the signed-in employee's QR without sending an employee ID", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ token: "signed" }) });
    vi.stubGlobal("fetch", fetchMock);
    await workflow.pass(new AbortController().signal);
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/workplace/v1/me/qr");
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ method: "POST", credentials: "include" });
  });

  it("loads scoped daily timesheets with the requested filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    await workflow.rows("2026-11", "NV001", new AbortController().signal);
    expect(fetchMock.mock.calls[0]?.[0]).toContain(
      "/attendance/v1/timesheets?month=2026-11&employeeId=NV001",
    );
    expect(fetchMock.mock.calls[0]?.[1]?.credentials).toBe("include");
  });

  it("downloads a monthly report using the session cookie", async () => {
    const csv = new Blob(["event_id"]);
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => csv });
    vi.stubGlobal("fetch", fetchMock);
    expect(await workflow.export("2026-11", "csv")).toBe(csv);
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/attendance/v1/timesheets/csv?month=2026-11");
    expect(fetchMock.mock.calls[0]?.[1]?.credentials).toBe("include");
  });
});
