import { useEffect, useRef, useState } from "react";
import { Camera, Check, ImageUp, ScanLine, X } from "lucide-react";
import { configuredEdges } from "../../shared/config/edges";
import { errorMessage } from "../../shared/lib/error-message";
import { DemoPage } from "../../shared/ui/DemoPage";
import { displayTime, groupLabel, workflow, type Station } from "../../shared/workflow";
import { accessApi } from "../api/access-api";
import { scanEventId } from "../lib/scan-event-id";
import { parseScanToken } from "../lib/scan-token";
import { readQrFile } from "../lib/read-qr";
import type { VerifyResult } from "../model/types";
import { QrCamera } from "./QrCamera";

type Gate = Station & { base: string; key: string };
type Scan = { result: VerifyResult; gate: Gate; at: string };
const reasons: Record<string, string> = {
  TOKEN_EXPIRED: "Mã QR đã hết hạn",
  REPLAY: "Mã QR đã được dùng",
  REVOKED: "Thẻ đã bị thu hồi",
  CVER_MISMATCH: "Mã cũ. Hãy mở mã QR mới",
  DEVICE_MISMATCH: "Thiết bị không khớp",
  ANTI_PASSBACK: "Trạng thái vào/ra không hợp lệ",
  ZONE_DENIED: "Bạn không có quyền vào khu vực này",
  OUTSIDE_SHIFT: "Ngoài khung giờ được vào",
  EMPLOYEE_INACTIVE: "Tài khoản chưa hoạt động",
  STATION_SCOPE: "Bạn không thuộc nhà máy này",
  STATION_AUTH_FAILED: "Cổng chưa sẵn sàng",
  INVALID_TOKEN: "Mã QR không hợp lệ",
};

function scanLabel({ result, gate }: Scan) {
  const entry = gate.gate.directions[0] !== "EXIT";
  if (result.decision !== "ALLOW") {
    if (result.reasonCode === "ANTI_PASSBACK")
      return entry ? "Bạn đã vào và chưa quét ra" : "Chưa có lượt vào phù hợp";
    return reasons[result.reasonCode ?? ""] ?? "Chưa thể xác nhận. Vui lòng gặp bảo vệ.";
  }
  if (result.command !== "ACKED") return "Chưa mở được cổng. Vui lòng gặp bảo vệ.";
  const name = result.employeeName ? `Xin chào ${result.employeeName}` : "Đã xác minh";
  if (result.attendanceGate === false) return `${name}, được phép vào khu vực`;
  return entry
    ? `${name}, đã ghi nhận giờ vào ${displayTime(result.scannedAt ?? null)}`
    : `Đã ghi nhận giờ ra ${displayTime(result.scannedAt ?? null)}`;
}

export function GatePage() {
  const [gates, setGates] = useState<Gate[]>([]);
  const [selection, setSelection] = useState("");
  const [camera, setCamera] = useState(false);
  const [raw, setRaw] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<Scan | null>(null);
  const [history, setHistory] = useState<Scan[]>([]);
  const lastCameraCode = useRef("");
  const gate = gates.find((item) => item.key === selection) ?? gates[0];
  useEffect(() => {
    const controller = new AbortController();
    const urls = Array.from(new Set(configuredEdges().map((edge) => edge.url)));
    void Promise.allSettled(
      urls.map(async (base) =>
        (await workflow.gates(base, controller.signal)).map((item) => ({
          ...item,
          base,
          key: `${base}/${item.stationId}`,
        })),
      ),
    ).then((responses) => {
      if (controller.signal.aborted) return;
      const items = responses.flatMap((response) =>
        response.status === "fulfilled" ? response.value : [],
      );
      setGates(items);
      if (!items.length) setError("Không tải được danh sách cổng.");
    });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    if (!result) return;
    const timer = window.setTimeout(() => setResult(null), 4500);
    return () => window.clearTimeout(timer);
  }, [result]);

  async function scan(read: () => string | undefined | Promise<string | undefined>) {
    if (!gate || busy) return;
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const code = await read();
      if (!code) throw new Error("Không tìm thấy mã QR trong ảnh.");
      const { token, deviceId } = parseScanToken(code);
      const scannedAt = new Date().toISOString();
      const verdict = await accessApi.verify(
        {
          eventId: scanEventId(),
          siteId: gate.siteId,
          direction: gate.gate.directions[0] ?? "ENTRY",
          scannedAt,
          token,
          deviceId,
        },
        gate.stationId,
        gate.base,
      );
      const entry = { gate, result: verdict, at: scannedAt };
      setResult(entry);
      setHistory((previous) => [entry, ...previous].slice(0, 5));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  const zone = gate?.gate.type === "ZONE_GATE";
  const good = result?.result.decision === "ALLOW" && result.result.command === "ACKED";
  const latest = history.find((scan) => scan.gate.key === gate?.key)?.result;
  return (
    <DemoPage title="Kiosk cổng">
      <div className="workflow-gate-grid">
        <section className="workflow-card workflow-gate">
          <label className="workflow-field">
            Chọn cổng
            <select
              disabled={!gate || busy}
              value={gate?.key ?? ""}
              onChange={(event) => {
                setSelection(event.target.value);
                lastCameraCode.current = "";
                setResult(null);
                setError("");
              }}
            >
              {!gate && <option value="">Đang tải cổng…</option>}
              {gates.map((item) => (
                <option value={item.key} key={item.key}>
                  {item.gate.name}
                </option>
              ))}
            </select>
          </label>
          <div className="workflow-gate-heading">
            <strong>
              {zone ? "CỔNG KHU VỰC" : gate?.gate.directions[0] === "EXIT" ? "CỔNG RA" : "CỔNG VÀO"}
            </strong>
            {gate && (
              <p>
                {zone
                  ? "Quyền vào · " +
                    gate.gate.allowedGroups.map((group) => groupLabel[group]).join(", ")
                  : "Ghi nhận giờ chấm công"}
              </p>
            )}
          </div>
          <div
            className={`workflow-scan ${result ? (good ? "accepted" : "rejected") : ""}`}
            role="status"
          >
            {result ? (
              <>
                {good ? <Check size={46} /> : <X size={46} />}
                <strong>{scanLabel(result)}</strong>
              </>
            ) : camera && !busy ? (
              <QrCamera
                ignoreCode={(code) => code === lastCameraCode.current}
                onDecode={(code) => {
                  lastCameraCode.current = code;
                  void scan(() => code);
                }}
                onFailure={(message) => {
                  setCamera(false);
                  setError(message);
                }}
              />
            ) : (
              <>
                <ScanLine size={45} />
                <strong>{busy ? "Đang xác minh…" : "Sẵn sàng quét QR"}</strong>
                {!busy && <p>Mở camera hoặc chọn ảnh QR của nhân viên.</p>}
              </>
            )}
          </div>
          <div className="workflow-actions">
            <button
              className="workflow-button"
              disabled={!gate || busy}
              onClick={() => {
                lastCameraCode.current = "";
                setCamera(!camera);
                setError("");
              }}
            >
              <Camera size={18} />
              {camera ? "Tắt camera" : "Mở camera"}
            </button>
            <label className="workflow-button secondary relative">
              <ImageUp size={18} /> Chọn ảnh QR
              <input
                className="absolute inset-0 w-full cursor-pointer opacity-0"
                type="file"
                accept="image/*"
                aria-label="Chọn ảnh QR"
                disabled={!gate || busy}
                onChange={(event) => {
                  const file = event.currentTarget.files?.[0];
                  event.currentTarget.value = "";
                  if (file) void scan(() => readQrFile(file));
                }}
              />
            </label>
          </div>
          {error && (
            <p role="alert" className="workflow-error">
              {error}
            </p>
          )}
          <details className="workflow-technical">
            <summary>Chi tiết kỹ thuật</summary>
            {gate && (
              <p>
                {gate.siteId} / {gate.stationId}
              </p>
            )}
            {latest && (
              <>
                <p>
                  {latest.decision} · {latest.reasonCode ?? latest.command} ·{" "}
                  {latest.evidence ?? "—"}
                </p>
                <p>Event ID {latest.eventId}</p>
                <ul>
                  {latest.steps?.map((step, index) => (
                    <li key={`${step}-${index}`}>{step}</li>
                  ))}
                </ul>
              </>
            )}
            <details className="workflow-technical">
              <summary>Nhập token thủ công (chỉ để test)</summary>
              <form
                className="grid gap-3 pt-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  void scan(() => raw);
                }}
              >
                <label className="workflow-field">
                  Token QR
                  <textarea value={raw} onChange={(event) => setRaw(event.target.value)} required />
                </label>
                <button className="workflow-button secondary" disabled={!gate || busy}>
                  Xác minh
                </button>
              </form>
            </details>
          </details>
        </section>
        <aside className="workflow-card workflow-history">
          <h2>5 lượt quét gần nhất</h2>
          {history.length ? (
            <ol>
              {history.map((item) => (
                <li key={item.result.eventId}>
                  <time dateTime={item.at}>{displayTime(item.at)}</time>
                  <div>
                    <strong>{scanLabel(item)}</strong>
                    <p>{item.gate.gate.name}</p>
                  </div>
                </li>
              ))}
            </ol>
          ) : (
            <p>Chưa có lượt quét.</p>
          )}
        </aside>
      </div>
    </DemoPage>
  );
}
