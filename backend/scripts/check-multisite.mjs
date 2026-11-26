import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

const bd = "http://127.0.0.1:8081";
const la = "http://127.0.0.1:8082";
const central = "http://127.0.0.1:8090";
const bearer = process.env.TMC_EMPLOYEE_DEMO_TOKEN;
const proof = process.env.TMC_DEVICE_PROOF;
assert(bearer && proof, "Set TMC_EMPLOYEE_DEMO_TOKEN and the device proof enrolled on BD.");

async function post(base, endpoint, body, headers) {
  const response = await fetch(base + endpoint, {
    method: "POST", headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body), signal: AbortSignal.timeout(5000),
  });
  const result = await response.json();
  assert.equal(response.status, 200, result.code ?? endpoint);
  return result;
}

async function suspiciousCount() {
  const response = await fetch(central + "/actuator/metrics/tmc.central.sync.suspicious", {
    signal: AbortSignal.timeout(5000),
  });
  if (response.status === 404) return 0;
  assert(response.ok, "Central metrics unavailable");
  return (await response.json()).measurements.find(({ statistic }) => statistic === "COUNT").value;
}

const before = await suspiciousCount();
const issue = () => post(bd, "/qr/v1/tokens", { credentialId: "demo-credential" }, {
  Authorization: `Bearer ${bearer}`, "X-Device-Proof": proof,
});
const targetSites = [[bd, "SITE-BD", "BD-G01"], [la, "SITE-LA", "LA-G01"]];
const scan = ([base, siteId, station], token, direction) => {
  const eventId = randomUUID();
  return post(base, "/access/v1/verify", {
    eventId, siteId, direction, scannedAt: new Date().toISOString(), token, deviceId: "demo-device",
  }, { "X-Station-Id": station, "Idempotency-Key": eventId });
};

// Normalize demo presence for repeat runs, with a distinct QR per Edge.
for (const target of targetSites) {
  const { token } = await issue();
  const result = await scan(target, token, "EXIT");
  assert(result.decision === "ALLOW" || result.reasonCode === "ANTI_PASSBACK", JSON.stringify(result));
}
const { token } = await issue();
const results = await Promise.all(targetSites.map((target) => scan(target, token, "ENTRY")));
for (const result of results) assert.equal(result.decision, "ALLOW", JSON.stringify(result));

for (let attempt = 0; attempt < 20; attempt += 1) {
  if (await suspiciousCount() > before) {
    console.log("BD ALLOW, LA ALLOW, Central SUSPICIOUS");
    process.exit(0);
  }
  await sleep(500);
}
throw new Error("Central did not record SUSPICIOUS within 10 seconds.");
