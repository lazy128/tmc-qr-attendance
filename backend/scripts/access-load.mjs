import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { createWriteStream } from "node:fs";
import { performance } from "node:perf_hooks";
import { setTimeout as sleep } from "node:timers/promises";

const url = new URL(process.env.EDGE_URL ?? "http://127.0.0.1:8080");
if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
  throw new Error("Run the sample against a local demo Edge instance.");
}
const site = process.env.SITE_ID ?? "SITE-1";
const station = process.env.STATION_ID ?? "GATE-1";
const scans = Number(process.env.SCAN_COUNT ?? 800);
const seconds = Number(process.env.DURATION_SECONDS ?? 1800);
if (!Number.isSafeInteger(scans) || scans < 1 || !Number.isFinite(seconds) || seconds <= 0) {
  throw new Error("Set SCAN_COUNT to a positive integer and DURATION_SECONDS to a positive number.");
}
const output = createWriteStream(process.env.LOAD_REPORT ?? "target/access-load.jsonl", { flags: "wx" });
await once(output, "open");

async function post(path, payload, headers = {}) {
  const response = await fetch(new URL(path, url), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(5000),
  });
  return { status: response.status, body: await response.json() };
}

const start = performance.now();
const latencies = [];
let acked = 0;
let verifyResponses = 0;
let errors = 0;
let exceeded = 0;
let unknown = 0;
let timeouts = 0;
let direction = "ENTRY";
for (let index = 0; index < scans; index += 1) {
  const due = start + index * seconds * 1000 / scans;
  if (performance.now() < due) await sleep(due - performance.now());
  const eventId = randomUUID();
  const row = { index: index + 1, eventId, site, direction, scanMs: null };
  let sentAt;
  try {
    const issue = await post("/qr/v1/tokens", {
      credentialId: "demo-credential", deviceHash: "demo-device",
    });
    if (issue.status !== 200 || !issue.body.token) throw new Error(`QR_ISSUE_HTTP_${issue.status}`);
    sentAt = performance.now();
    const verify = await post("/access/v1/verify", {
      eventId, siteId: site, direction, scannedAt: new Date().toISOString(),
      token: issue.body.token, deviceId: "demo-device",
    }, { "X-Station-Id": station, "Idempotency-Key": eventId });
    verifyResponses += 1;
    row.http = verify.status;
    row.decision = verify.body.decision;
    row.command = verify.body.command;
    row.reason = verify.body.reasonCode;
    if (verify.status === 200 && row.decision === "ALLOW" && row.command === "ACKED") {
      acked += 1;
      direction = direction === "ENTRY" ? "EXIT" : "ENTRY";
    } else {
      errors += 1;
      if (row.command === "UNKNOWN") unknown += 1;
    }
  } catch (error) {
    errors += 1;
    if (error.name === "TimeoutError") timeouts += 1;
    row.error = error.name === "TimeoutError" ? "TIMEOUT" : error.message;
    row.phase = sentAt === undefined ? "issue" : "verify";
  } finally {
    if (sentAt !== undefined) {
      row.scanMs = Number((performance.now() - sentAt).toFixed(2));
      latencies.push(row.scanMs);
      if (row.scanMs > 2000) exceeded += 1;
    }
    if (!output.write(`${JSON.stringify(row)}\n`)) await once(output, "drain");
  }
}
output.end();
await once(output, "finish");

const maxMs = latencies.length ? Math.max(...latencies) : null;
console.log(JSON.stringify({
  site, station, scheduledScans: scans, verifyResponses,
  acked, errors, timeouts, unknown, aboveTwoSeconds: exceeded, maxMs,
  runSeconds: Number(((performance.now() - start) / 1000).toFixed(2)),
}));
if (errors || exceeded) process.exitCode = 1;
