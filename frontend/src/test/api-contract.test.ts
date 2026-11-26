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

  it("requests a QR for the selected demo employee", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ token: "signed" }) });
    vi.stubGlobal("fetch", fetchMock);
    await workflow.pass("NV001", new AbortController().signal);
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/demo/v1/employees/NV001/qr");
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ method: "POST" });
  });

  it("loads scoped daily timesheets with the requested filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    await workflow.rows("test-token", "2026-11", "NV001", new AbortController().signal);
    expect(fetchMock.mock.calls[0]?.[0]).toContain(
      "/attendance/v1/timesheets?month=2026-11&employeeId=NV001",
    );
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization")).toBe(
      "Bearer test-token",
    );
  });

  it("downloads a monthly report using the HR bearer token", async () => {
    const csv = new Blob(["event_id"]);
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => csv });
    vi.stubGlobal("fetch", fetchMock);
    expect(await workflow.export("hr", "2026-11", "csv")).toBe(csv);
    expect(fetchMock.mock.calls[0]?.[0]).toContain("/attendance/v1/timesheets/csv?month=2026-11");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization")).toBe(
      "Bearer hr",
    );
  });
});
