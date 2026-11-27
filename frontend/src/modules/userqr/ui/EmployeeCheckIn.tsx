import { useEffect, useEffectEvent, useRef, useState, type ChangeEvent } from "react";
import { Camera } from "lucide-react";
import { QrCamera } from "../../access/ui/QrCamera";
import { readQrFile } from "../../access/lib/read-qr";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { checkInCodeFromQr, type CheckInCode } from "../../shared/lib/qr-link";
import { displayTime, workflow, type StationCheckIn } from "../../shared/workflow";

function outcome(result: StationCheckIn) {
  if (result.command === "SENT" || result.command === "UNKNOWN") {
    return "Lượt chấm công đang chờ kết quả. Bấm Kiểm tra lại để xem trạng thái.";
  }
  if (result.decision === "ALLOW" && result.command === "ACKED") {
    return `Đã ghi nhận giờ ${result.direction === "ENTRY" ? "vào" : "ra"} · ${displayTime(result.scannedAt)}`;
  }
  if (result.reasonCode === "ANTI_PASSBACK") {
    return result.direction === "ENTRY"
      ? "Bạn đã có giờ vào."
      : "Chưa có giờ vào phù hợp để ghi giờ ra.";
  }
  if (result.reasonCode === "OUTSIDE_SHIFT") return "Lượt quét ngoài khung giờ quy định.";
  if (result.reasonCode === "SHIFT_COMPLETED") return "Ngày công đã kết thúc.";
  return "Chưa thể ghi nhận lượt chấm công. Kiểm tra cổng và thử lại.";
}

export function EmployeeCheckIn({
  linkedCode,
  confirmationView,
  finished,
  clearLink,
  onRecorded,
}: {
  linkedCode: CheckInCode | null;
  confirmationView: boolean;
  finished: boolean;
  clearLink: () => void;
  onRecorded: () => void;
}) {
  const request = useRef<AbortController | null>(null);
  const [camera, setCamera] = useState(false);
  const [busy, setBusy] = useState(false);
  const [retryCode, setRetryCode] = useState<CheckInCode | null>(null);
  const [result, setResult] = useState<StationCheckIn | null>(null);
  const [error, setError] = useState("");
  const canStream =
    typeof navigator !== "undefined" &&
    Boolean(globalThis.isSecureContext && navigator.mediaDevices?.getUserMedia);
  useEffect(() => () => request.current?.abort(), []);

  const confirmPersonal = useEffectEvent((scan: CheckInCode) => void submit(scan));
  useEffect(() => {
    if (linkedCode?.kind !== "personal") return;
    const timer = window.setTimeout(() => confirmPersonal(linkedCode), 0);
    return () => window.clearTimeout(timer);
  }, [linkedCode]);

  async function submit(scan: CheckInCode) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setCamera(false);
    setError("");
    setResult(null);
    setRetryCode(null);
    clearLink();
    try {
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(6000)]);
      const record = scan.kind === "personal" ? workflow.personalCheckIn : workflow.checkIn;
      const response = await record(scan.code, signal);
      if (controller.signal.aborted) return;
      setResult(response);
      if (response.command === "SENT" || response.command === "UNKNOWN") setRetryCode(scan);
      if (response.decision === "ALLOW" && response.command === "ACKED") onRecorded();
    } catch (cause) {
      if (controller.signal.aborted) return;
      if (!(cause instanceof ApiRequestError) || cause.retryable) {
        setRetryCode(scan);
        setError("Chưa nhận được kết quả. Hãy kiểm tra kết nối rồi bấm Kiểm tra lại.");
      } else {
        setError(errorMessage(cause));
      }
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  function scanned(raw: string) {
    if (request.current) return;
    setCamera(false);
    const scan = checkInCodeFromQr(raw.trim());
    if (scan) void submit(scan);
    else {
      setResult(null);
      setRetryCode(null);
      setError("Hãy quét mã QR chấm công đang hiển thị.");
    }
  }

  async function selectImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (!file) return;
    try {
      const raw = await readQrFile(file);
      if (raw) scanned(raw);
      else setError("Không tìm thấy mã QR trong ảnh.");
    } catch (cause) {
      setError(errorMessage(cause));
    }
  }

  return (
    <section className="workflow-card workflow-pass grid gap-3">
      <h2>{confirmationView ? "Xác nhận chấm công" : "Quét QR chấm công"}</h2>
      {finished && !busy && !result && !error && <p>Đã kết thúc ngày công.</p>}
      {!finished &&
        (linkedCode?.kind === "station" ? (
          <p>Đã đọc mã tại cổng. Bấm Xác nhận chấm công để ghi giờ vào hoặc ra.</p>
        ) : (
          !confirmationView && <p>Dùng camera để quét QR đang hiển thị trên máy tính.</p>
        ))}
      {!finished && camera && (
        <div className="workflow-camera">
          <QrCamera
            paused={busy}
            onDecode={scanned}
            onFailure={(message) => {
              setCamera(false);
              setError(message);
            }}
          />
        </div>
      )}
      {busy && <p role="status">Đang ghi nhận lượt chấm công…</p>}
      {result && (
        <p
          className={
            result.decision === "ALLOW" && result.command === "ACKED" ? "workflow-today" : undefined
          }
          role="status"
        >
          {outcome(result)}
        </p>
      )}
      {error && (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      )}
      {(!finished || retryCode) && (
        <div className="workflow-actions">
          {!finished && linkedCode?.kind === "station" ? (
            <>
              <button
                type="button"
                className="workflow-button"
                onClick={() => void submit(linkedCode)}
              >
                Xác nhận chấm công
              </button>
              <button type="button" className="workflow-button secondary" onClick={clearLink}>
                Quét mã khác
              </button>
            </>
          ) : (
            <>
              {!finished && canStream && (
                <button
                  type="button"
                  className="workflow-button"
                  disabled={busy}
                  onClick={() => {
                    setError("");
                    setCamera((open) => !open);
                  }}
                >
                  <Camera size={18} /> {camera ? "Tắt camera chấm công" : "Mở camera chấm công"}
                </button>
              )}
              {!finished && (
                <label className="workflow-button secondary relative">
                  {canStream ? "Chọn ảnh QR chấm công" : "Chụp mã QR chấm công"}
                  <input
                    className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
                    type="file"
                    accept="image/*"
                    capture={canStream ? undefined : "environment"}
                    aria-label={canStream ? "Chọn ảnh QR chấm công" : "Chụp mã QR chấm công"}
                    disabled={busy}
                    onChange={(event) => void selectImage(event)}
                  />
                </label>
              )}
              {retryCode && (
                <button
                  type="button"
                  className="workflow-button secondary"
                  disabled={busy}
                  onClick={() => void submit(retryCode)}
                >
                  Kiểm tra lại
                </button>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
