import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { Camera, Check, ImageUp, ScanLine, X } from "lucide-react";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { tokenFromQrLink } from "../../shared/lib/qr-link";
import { AppPage } from "../../shared/ui/AppPage";
import { RequireAccount } from "../../shared/ui/Auth";
import { displayTime, siteName, workflow, type Station } from "../../shared/workflow";
import { accessApi } from "../api/access-api";
import { scanEventId } from "../lib/scan-event-id";
import { parseScanToken } from "../lib/scan-token";
import { readQrFile } from "../lib/read-qr";
import type { VerifyPayload, VerifyResult } from "../model/types";
import { QrCamera } from "./QrCamera";
import { GateIncidentQueue } from "./GateIncidentQueue";
import { StationCodePanel } from "./StationCodePanel";

const reasons: Record<string, string> = {
  TOKEN_EXPIRED: "Mã QR đã hết hạn. Hãy quét mã mới.",
  REPLAY: "Mã QR này đã được sử dụng.",
  REVOKED: "Thẻ đã bị thu hồi.",
  CVER_MISMATCH: "Mã đã thay đổi. Hãy mở mã QR mới.",
  DEVICE_MISMATCH: "Mã QR không khớp với thẻ nhân viên.",
  ZONE_DENIED: "Nhân viên không có quyền vào khu vực.",
  OUTSIDE_SHIFT: "Lượt quét ngoài khung giờ quy định.",
  EMPLOYEE_INACTIVE: "Tài khoản chưa hoạt động.",
  STATION_SCOPE: "Nhân viên không thuộc nhà máy này.",
  STATION_AUTH_FAILED: "Trạm quét hiện không hoạt động.",
  INVALID_TOKEN: "Mã QR không hợp lệ.",
  COMMAND_PENDING: "Lượt trước đang chờ xác nhận cổng.",
  SHIFT_COMPLETED: "Ngày công của nhân viên đã kết thúc.",
};

type Pending = { id: string; payload?: VerifyPayload };

function scanLabel(result: VerifyResult, direction: "ENTRY" | "EXIT") {
  if (result.decision !== "ALLOW") {
    if (result.reasonCode === "ANTI_PASSBACK") {
      return direction === "ENTRY"
        ? "Đã có giờ vào. Cần quét ra trước."
        : "Chưa có lượt vào phù hợp.";
    }
    return (
      reasons[result.reasonCode ?? ""] ??
      "Lượt quét bị từ chối. Liên hệ quản trị viên nếu cần hỗ trợ."
    );
  }
  if (result.evidence === "MANUAL_NO_PASSAGE") return "Nhân sự xác nhận lượt này chưa qua cổng.";
  if (result.command === "EXPIRED" && result.evidence !== "MANUAL_PASSAGE") {
    return "Lượt cổng đã chuyển cho Nhân sự đối soát. Có thể quét mã mới.";
  }
  if (result.command === "FAILED")
    return "Cổng chưa xác nhận lượt này. Hãy kiểm tra trạm rồi quét mã mới.";
  if (result.command !== "ACKED" && result.evidence !== "MANUAL_PASSAGE")
    return "Chưa thể ghi nhận lượt quét.";
  if (result.attendanceGate === false)
    return `${result.employeeName ?? "Nhân viên"} được phép vào khu vực`;
  const time = displayTime(result.scannedAt ?? null);
  return `${result.employeeName ?? "Nhân viên"} · Đã ghi nhận giờ ${direction === "ENTRY" ? "vào" : "ra"} ${time}`;
}

function lastPending(key: string): Pending | null {
  try {
    const id = window.sessionStorage.getItem(key);
    return id ? { id } : null;
  } catch {
    return null;
  }
}

function savePending(key: string, id?: string) {
  try {
    if (id) window.sessionStorage.setItem(key, id);
    else window.sessionStorage.removeItem(key);
  } catch {
    /* The active tab can still retry without browser storage. */
  }
}

export function GatePage() {
  const [linkedCode, setLinkedCode] = useState("");
  useEffect(() => {
    function readLink() {
      const code = tokenFromQrLink(window.location.href);
      if (!code) return;
      setLinkedCode(code);
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname + window.location.search,
      );
    }
    readLink();
    window.addEventListener("hashchange", readLink);
    return () => window.removeEventListener("hashchange", readLink);
  }, []);
  return (
    <AppPage title="Trạm quét">
      <RequireAccount
        roles={["GUARD"]}
        loginHint={
          linkedCode
            ? "Đăng nhập bằng tài khoản bảo vệ của trạm. Nếu mã vừa quét hết hạn trong lúc đăng nhập, hãy quét mã mới bằng camera trong Trạm quét."
            : "Đăng nhập bằng tài khoản bảo vệ của trạm, sau đó mở camera để quét mã nhân viên."
        }
      >
        {(account) => (
          <AssignedStation
            key={account.username}
            username={account.username}
            linkedCode={linkedCode}
            clearLink={() => setLinkedCode("")}
          />
        )}
      </RequireAccount>
    </AppPage>
  );
}

function AssignedStation({
  username,
  linkedCode,
  clearLink,
}: {
  username: string;
  linkedCode: string;
  clearLink: () => void;
}) {
  const [station, setStation] = useState<Station | null>(null);
  const [displayCode, setDisplayCode] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void workflow
      .station(controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setStation(value);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [revision]);
  if (error)
    return (
      <section className="workflow-card workflow-login">
        <p className="workflow-error" role="alert">
          {error}
        </p>
        <button
          className="workflow-button"
          onClick={() => {
            setError("");
            setRevision((value) => value + 1);
          }}
        >
          Thử lại
        </button>
      </section>
    );
  if (!station) return <p role="status">Đang tải trạm được giao…</p>;
  if (displayCode && !linkedCode) {
    return <StationCodePanel station={station} close={() => setDisplayCode(false)} />;
  }
  return (
    <div className="grid gap-4">
      {station.gate.type === "ATTENDANCE_GATE" && !linkedCode && (
        <button
          type="button"
          className="workflow-button secondary w-fit"
          onClick={() => setDisplayCode(true)}
        >
          Hiện QR cho nhân viên quét
        </button>
      )}
      {!linkedCode && <GateIncidentQueue />}
      <StationScanner
        key={station.stationId}
        station={station}
        linkedCode={linkedCode}
        clearLink={clearLink}
        storageKey={`tmc.pending.${username}.${station.stationId}`}
      />
    </div>
  );
}

function StationScanner({
  station,
  linkedCode,
  clearLink,
  storageKey,
}: {
  station: Station;
  linkedCode: string;
  clearLink: () => void;
  storageKey: string;
}) {
  const [pending, setPending] = useState<Pending | null>(() => lastPending(storageKey));
  const [camera, setCamera] = useState(false);
  const [reading, setReading] = useState(false);
  const [wait, setWait] = useState("");
  const [error, setError] = useState("");
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [history, setHistory] = useState<VerifyResult[]>([]);
  const busy = useRef(false);
  const lastCameraCode = useRef("");
  const retry = useRef<(() => void) | null>(null);
  const direction = station.gate.directions[0] ?? "ENTRY";
  const canStream = Boolean(globalThis.isSecureContext && navigator.mediaDevices?.getUserMedia);
  const good =
    result?.decision === "ALLOW" &&
    (result.command === "ACKED" || result.evidence === "MANUAL_PASSAGE");

  useEffect(() => {
    if (!pending) return;
    const event = pending;
    const controller = new AbortController();
    let timer = 0;
    let inFlight = false;
    let retries = 0;
    let missing = 0;
    const interrupted = () =>
      setWait("Mất kết nối. Đang giữ lượt quét để kiểm tra lại khi có mạng.");
    function schedule() {
      if (controller.signal.aborted) return;
      window.clearTimeout(timer);
      timer = window.setTimeout(send, Math.min(2000 * 2 ** Math.min(retries++, 3), 10_000));
    }
    function finish() {
      savePending(storageKey);
      setPending(null);
      setWait("");
    }
    async function send() {
      if (inFlight || controller.signal.aborted) return;
      window.clearTimeout(timer);
      if (!navigator.onLine) {
        interrupted();
        return;
      }
      inFlight = true;
      setWait("Đang đối chiếu với máy chủ…");
      try {
        const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(6000)]);
        const response = event.payload
          ? await accessApi.verify(event.payload, station.stationId, signal)
          : await accessApi.result(event.id, station.stationId, signal);
        if (controller.signal.aborted) return;
        if (response.command === "SENT" || response.command === "UNKNOWN") {
          setWait("Máy chủ đã nhận lượt quét. Đang chờ kết quả.");
          schedule();
          return;
        }
        setResult(response);
        setHistory((previous) =>
          [response, ...previous.filter((item) => item.eventId !== response.eventId)].slice(0, 5),
        );
        finish();
      } catch (cause) {
        if (controller.signal.aborted) return;
        if (cause instanceof ApiRequestError && !cause.retryable) {
          if (cause.status === 401) return; // The account form takes over; keep the event ID for recovery.
          if (!event.payload && cause.status === 404 && ++missing < 3) {
            schedule();
            return;
          }
          setError(
            cause.status === 404
              ? "Máy chủ chưa có kết quả cho lượt quét này. Kiểm tra kết nối rồi quét mã mới."
              : errorMessage(cause),
          );
          finish();
        } else {
          setWait(
            cause instanceof ApiRequestError && cause.code === "REQUEST_IN_PROGRESS"
              ? "Máy chủ đang xử lý lượt này. Đang chờ kết quả."
              : "Chưa nhận được phản hồi. Hệ thống sẽ thử lại cùng lượt quét.",
          );
          schedule();
        }
      } finally {
        inFlight = false;
      }
    }
    retry.current = () => void send();
    window.addEventListener("online", send);
    window.addEventListener("offline", interrupted);
    void send();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
      retry.current = null;
      window.removeEventListener("online", send);
      window.removeEventListener("offline", interrupted);
    };
  }, [pending, station.stationId, storageKey]);

  async function scan(read: () => string | undefined | Promise<string | undefined>) {
    if (busy.current || pending) return;
    busy.current = true;
    setReading(true);
    setError("");
    setResult(null);
    try {
      const code = await read();
      if (!code) throw new Error("Không thấy mã QR. Hãy chụp rõ toàn bộ mã.");
      const { token, deviceId } = parseScanToken(code);
      const eventId = scanEventId();
      const payload = {
        eventId,
        token,
        deviceId,
        siteId: station.siteId,
        direction,
        scannedAt: new Date().toISOString(),
      };
      savePending(storageKey, eventId);
      setPending({ id: eventId, payload });
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setReading(false);
      busy.current = false;
    }
  }

  function scanImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (file) void scan(() => readQrFile(file));
  }

  return (
    <div className="workflow-gate-grid">
      <section className="workflow-card workflow-gate">
        <div className="workflow-gate-heading">
          <strong>{station.gate.name}</strong>
          <p>
            {station.stationId} · {siteName(station.siteId)}
          </p>
        </div>
        {camera && (
          <div className="workflow-camera">
            <QrCamera
              paused={Boolean(pending || reading || linkedCode)}
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
          </div>
        )}
        <div
          className={`workflow-scan${camera ? " with-camera" : ""}${result && !pending ? (good ? " accepted" : " rejected") : ""}`}
          role="status"
        >
          {pending ? (
            <>
              <ScanLine size={38} />
              <strong>Lượt quét đang chờ</strong>
              <p>{wait}</p>
              <p>Mã lượt {pending.id}</p>
            </>
          ) : result && !linkedCode ? (
            <>
              {good ? <Check size={46} /> : <X size={46} />}
              <strong>{scanLabel(result, direction)}</strong>
            </>
          ) : (
            <>
              <ScanLine size={45} />
              <strong>
                {reading
                  ? "Đang đọc mã…"
                  : linkedCode
                    ? "Đã đọc mã QR"
                    : camera
                      ? "Đưa mã QR vào khung hình"
                      : "Sẵn sàng quét QR"}
              </strong>
              {linkedCode && <p>Xác nhận khi nhân viên có mặt tại cổng.</p>}
            </>
          )}
        </div>
        {error && (
          <p role="alert" className="workflow-error">
            {error}
          </p>
        )}
        <div className="workflow-actions">
          {pending ? (
            <button className="workflow-button" onClick={() => retry.current?.()}>
              Thử lại lượt quét
            </button>
          ) : linkedCode ? (
            <>
              <button
                className="workflow-button"
                disabled={reading}
                onClick={() => {
                  clearLink();
                  void scan(() => linkedCode);
                }}
              >
                <Check size={18} /> Xác nhận quét
              </button>
              <button
                type="button"
                className="workflow-button secondary"
                onClick={() => {
                  setResult(null);
                  setError("");
                  clearLink();
                }}
              >
                Quét mã khác
              </button>
            </>
          ) : canStream ? (
            <button
              className="workflow-button"
              disabled={reading}
              onClick={() => {
                lastCameraCode.current = "";
                setCamera(!camera);
                setResult(null);
                setError("");
              }}
            >
              <Camera size={18} /> {camera ? "Tắt camera" : "Mở camera"}
            </button>
          ) : (
            <label className="workflow-button relative">
              <Camera size={18} /> Chụp mã QR
              <input
                className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
                type="file"
                accept="image/*"
                capture="environment"
                aria-label="Chụp mã QR"
                disabled={reading}
                onChange={scanImage}
              />
            </label>
          )}
          {!pending && !linkedCode && (
            <label className="workflow-button secondary relative">
              <ImageUp size={18} /> Chọn ảnh QR
              <input
                className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
                type="file"
                accept="image/*"
                aria-label="Chọn ảnh QR"
                disabled={reading}
                onChange={scanImage}
              />
            </label>
          )}
        </div>
      </section>
      <aside className="workflow-card workflow-history">
        <h2>Lượt quét gần đây</h2>
        {history.length ? (
          <ol>
            {history.map((item) => (
              <li key={item.eventId}>
                <time dateTime={item.scannedAt}>{displayTime(item.scannedAt ?? null)}</time>
                <strong>{scanLabel(item, direction)}</strong>
              </li>
            ))}
          </ol>
        ) : (
          <p>Chưa có lượt quét trong phiên này.</p>
        )}
      </aside>
    </div>
  );
}
