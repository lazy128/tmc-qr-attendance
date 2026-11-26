import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

const central = "http://127.0.0.1:8090";
const bd = "http://127.0.0.1:8081";
const la = "http://127.0.0.1:8082";

async function api(base, path, body, headers = {}) {
  const response = await fetch(base + path, {
    ...(body === undefined ? {} : { method: "POST", body: JSON.stringify(body) }),
    headers: { "Content-Type": "application/json", ...headers },
    signal: AbortSignal.timeout(5000),
  });
  assert.equal(response.status, 200, `${path} HTTP ${response.status}`);
  return response.json();
}

const wait = async (action) => {
  for (let attempt = 0; attempt < 40; attempt++) {
    if (await action()) return;
    await sleep(500);
  }
  assert.fail("Timed out waiting for synced timesheet events");
};

const { bearer } = await api(central, "/demo/v1/session", { role: "HR" });
const scan = async (employee, url, site, station, direction) => {
  const { token } = await api(central, `/demo/v1/employees/${employee}/qr`, {});
  const { did: deviceId } = JSON.parse(Buffer.from(token.split(".")[1], "base64url"));
  const eventId = randomUUID();
  return api(url, "/access/v1/verify", { eventId, siteId: site, direction, token,
    deviceId, scannedAt: new Date().toISOString() }, { "X-Station-Id": station, "Idempotency-Key": eventId });
};
assert.equal((await scan("NV001", la, "SITE-LA", "LA-G01", "ENTRY")).reasonCode, "STATION_SCOPE");
const zone = await scan("NV001", bd, "SITE-BD", "BD-G01-PROD", "ENTRY");
assert.equal(zone.attendanceGate, false);
assert.equal(zone.decision, "ALLOW");
for (const [employee, url, site, station] of [
  ["NV001", bd, "SITE-BD", "BD-G01"], ["NV006", la, "SITE-LA", "LA-G01"],
]) {
  const entry = await scan(employee, url, site, station, "ENTRY");
  const exit = await scan(employee, url, site, station + "-OUT", "EXIT");
  assert.equal(entry.decision, "ALLOW");
  assert.equal(exit.decision, "ALLOW");
  const date = new Date(Date.parse(entry.scannedAt) + 3_600_000).toISOString().slice(0, 10);
  const detail = "/attendance/v1/timesheets/details?" + new URLSearchParams({ employeeId: employee, date });
  await wait(async () => {
    const rows = await api(central, detail, undefined, { Authorization: `Bearer ${bearer}` });
    assert(!rows.some((row) => row.eventId === zone.eventId));
    return rows.some((row) => row.eventId === entry.eventId) && rows.some((row) => row.eventId === exit.eventId);
  });
}
console.log("Workflow multisite: BD and LA attendance reached Central, zone access did not.");
