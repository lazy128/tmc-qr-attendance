import { useState } from "react";
import { configuredEdges } from "../../shared/config/edges";
import { errorMessage } from "../../shared/lib/error-message";
import { DemoPage } from "../../shared/ui/DemoPage";
import { accessApi } from "../api/access-api";
import { scanEventId } from "../lib/scan-event-id";
import { parseScanToken } from "../lib/scan-token";
import type { VerifyPayload, VerifyResult } from "../model/types";
import { QrCamera } from "./QrCamera";

type Scan = { time: string; station: string; result: VerifyResult };

export function GatePage() {
  const edges = configuredEdges();
  const [selection, setSelection] = useState(0);
  const [direction, setDirection] = useState<VerifyPayload["direction"]>("ENTRY");
  const [camera, setCamera] = useState(false);
  const [manual, setManual] = useState(false);
  const [raw, setRaw] = useState("");
  const [override, setOverride] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [history, setHistory] = useState<Scan[]>([]);
  const edge = edges[selection] ?? edges[0];

  async function scan(value: string, deviceOverride?: string) {
    setCamera(false);
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const { token, deviceId } = parseScanToken(value);
      const scannedAt = new Date().toISOString();
      const verification = await accessApi.verify(
        {
          eventId: scanEventId(),
          siteId: edge.site,
          direction,
          scannedAt,
          token,
          deviceId: deviceOverride?.trim() || deviceId,
        },
        edge.station,
        edge.url,
      );
      setResult(verification);
      setHistory((previous) =>
        [{ time: scannedAt, station: edge.station, result: verification }, ...previous].slice(
          0,
          10,
        ),
      );
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <DemoPage title="Kiosk cổng">
      <div className="grid gap-4">
        <section className="facility-card grid gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="demo-field">
              Site / trạm
              <select
                value={selection}
                disabled={busy}
                onChange={(event) => {
                  setCamera(false);
                  setResult(null);
                  setError("");
                  setSelection(Number(event.target.value));
                }}
              >
                {edges.map((item, index) => (
                  <option key={index} value={index}>
                    {item.site} / {item.station}
                  </option>
                ))}
              </select>
            </label>
            <label className="demo-field">
              Chiều
              <select
                value={direction}
                disabled={busy || camera}
                onChange={(event) => setDirection(event.target.value as VerifyPayload["direction"])}
              >
                <option value="ENTRY">Vào</option>
                <option value="EXIT">Ra</option>
              </select>
            </label>
          </div>
          <div className="facility-actions">
            <button
              className="facility-button facility-button--primary"
              disabled={busy}
              onClick={() => {
                setError("");
                setCamera(!camera);
              }}
            >
              {camera ? "Tắt camera" : "Mở camera"}
            </button>
            <button
              className="facility-button"
              aria-expanded={manual}
              onClick={() => {
                setCamera(false);
                setManual(!manual);
              }}
            >
              Nhập tay token
            </button>
          </div>
          {camera && (
            <QrCamera
              onDecode={(code) => void scan(code)}
              onFailure={(message) => {
                setCamera(false);
                setError(message);
              }}
            />
          )}
          {manual && (
            <form
              className="grid gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                void scan(raw, override);
              }}
            >
              <label className="demo-field">
                Token QR{" "}
                <textarea value={raw} onChange={(event) => setRaw(event.target.value)} required />
              </label>
              <label className="demo-field">
                Device ID để thử sai thiết bị
                <input
                  value={override}
                  maxLength={256}
                  onChange={(event) => setOverride(event.target.value)}
                />
              </label>
              <button className="facility-button self-start" disabled={busy}>
                Xác minh
              </button>
            </form>
          )}
          {busy && <p role="status">Đang xác minh…</p>}
          {error && (
            <p className="facility-error" role="alert">
              {error}
            </p>
          )}
        </section>
        {result && (
          <section className="facility-card text-center" role="status">
            <strong
              className={`text-5xl ${result.decision === "ALLOW" ? "text-green-800" : "text-red-800"}`}
            >
              {result.decision}
            </strong>
            {result.reasonCode && <p className="mt-2 font-bold">{result.reasonCode}</p>}
            <p className="mt-2">{result.kioskMessage}</p>
          </section>
        )}
        <section className="facility-card">
          <h2>Lịch sử quét</h2>
          {history.length ? (
            <ol className="mt-3 grid gap-2">
              {history.map((item) => (
                <li key={item.result.eventId} className="flex flex-wrap gap-x-3 border-b py-2">
                  <time dateTime={item.time}>
                    {new Date(item.time).toLocaleTimeString("vi-VN")}
                  </time>
                  <span>{item.station}</span>
                  <strong>{item.result.decision}</strong>
                  <span>{item.result.reasonCode}</span>
                </li>
              ))}
            </ol>
          ) : (
            <p className="facility-muted mt-2">Chưa có lượt quét.</p>
          )}
        </section>
      </div>
    </DemoPage>
  );
}
