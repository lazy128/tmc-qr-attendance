import { useEffect, useEffectEvent, useRef, useState } from "react";
import { BadgeCheck, Clock3, QrCode } from "lucide-react";
import QRCode from "qrcode";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { employeeQrPayload } from "../../shared/lib/qr-link";
import { AppPage } from "../../shared/ui/AppPage";
import { RequireAccount } from "../../shared/ui/Auth";
import {
  displayTime,
  groupLabel,
  workflow,
  type AttendanceStatus,
  type Employee,
  type Pass,
} from "../../shared/workflow";

const phoneRefreshLeadMs = 18_000;
const phoneScanWindowMs = 15_000;
const kioskRefreshLeadMs = 8_000;

function EmployeeQr({ payload }: { payload: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    let active = true;
    let observer: ResizeObserver | undefined;
    const renderScale = 6;
    QRCode.toCanvas(element, payload, {
      scale: renderScale,
      margin: 4,
      errorCorrectionLevel: "M",
    })
      .then(() => {
        const container = element.parentElement;
        if (!active || !container || typeof ResizeObserver === "undefined") return;
        const modules = element.width / renderScale;
        observer = new ResizeObserver(([entry]) => {
          if (!entry) return;
          // Give every QR module an equal number of screen pixels.
          const pixelsPerModule = Math.max(1, Math.floor(entry.contentRect.width / modules));
          element.style.width = `${pixelsPerModule * modules}px`;
        });
        observer.observe(container);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
      observer?.disconnect();
    };
  }, [payload]);
  if (failed) return <p role="alert">Không thể hiển thị mã QR.</p>;
  return <canvas ref={canvas} role="img" aria-label="Mã QR truy cập" />;
}

type TokenDetails = { jti?: string; cver?: number; kid?: string; iat?: number; exp?: number };

function tokenDetails(token: string): TokenDetails {
  try {
    const [header = "", payload = ""] = token.split(".");
    const decode = (part: string) =>
      JSON.parse(atob(part.replaceAll("-", "+").replaceAll("_", "/")));
    const claims = decode(payload) as TokenDetails;
    const { kid } = decode(header) as TokenDetails;
    return kid ? { ...claims, kid } : claims;
  } catch {
    return {};
  }
}

function EmployeePass({ employee }: { employee: Employee }) {
  const [qr, setQr] = useState<{ pass: Pass; payload: string; deadline: number } | null>(null);
  const [liveAttendance, setLiveAttendance] = useState<AttendanceStatus | null>(null);
  const [now, setNow] = useState(0);
  const [error, setError] = useState("");
  const [revoked, setRevoked] = useState(false);
  const fetchPass = useEffectEvent(async (signal: AbortSignal) => {
    const requestedAt = performance.now();
    try {
      const issued = await workflow.pass(signal);
      if (signal.aborted) return;
      if (issued.employeeId !== employee.id) {
        setQr(null);
        window.dispatchEvent(new Event("tmc-session-changed"));
        return;
      }
      const receivedAt = performance.now();
      const { iat, exp } = tokenDetails(issued.token);
      // The signed timestamps are rounded down to seconds. Budget for that and the request time.
      const deadline =
        iat !== undefined && exp !== undefined
          ? requestedAt + (exp - iat - 1) * 1000
          : receivedAt + (Date.parse(issued.expiresAt) - Date.now());
      setNow(receivedAt);
      setQr({
        pass: issued,
        payload: employeeQrPayload(issued.token, window.location.origin),
        deadline,
      });
    } catch (cause) {
      if (signal.aborted) return;
      if (cause instanceof ApiRequestError && cause.code === "REVOKED") {
        setQr(null);
        setRevoked(true);
      } else {
        setError(errorMessage(cause));
      }
    }
  });
  const deadline = qr?.deadline;
  const phoneLink = qr?.payload.startsWith("http") ?? false;
  const refreshLead = phoneLink ? phoneRefreshLeadMs : kioskRefreshLeadMs;
  useEffect(() => {
    if (revoked || error) return;
    const controller = new AbortController();
    const delay =
      deadline === undefined ? 0 : Math.max(1000, deadline - performance.now() - refreshLead);
    const timer = window.setTimeout(() => void fetchPass(controller.signal), delay);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [employee.id, deadline, refreshLead, error, revoked]);
  useEffect(() => {
    if (deadline === undefined) return;
    const timer = window.setInterval(() => setNow(performance.now()), 250);
    return () => window.clearInterval(timer);
  }, [deadline]);
  useEffect(() => {
    const controller = new AbortController();
    let timer = window.setTimeout(refreshAttendance, 2000);
    async function refreshAttendance() {
      try {
        const latest = await workflow.attendance(controller.signal);
        if (controller.signal.aborted) return;
        if (latest.employeeId !== employee.id) {
          setQr(null);
          window.dispatchEvent(new Event("tmc-session-changed"));
          return;
        }
        setLiveAttendance(latest);
      } catch {
        // Keep the latest known attendance while the connection recovers.
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(refreshAttendance, 2000);
      }
    }
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [employee.id]);
  const pass = qr?.pass;
  const remainingMs = Math.max(0, (deadline ?? now) - now);
  const remaining = Math.ceil(remainingMs / 1000);
  const canScan = remainingMs > (phoneLink ? phoneScanWindowMs : 0);
  const status = revoked
    ? "Đã thu hồi"
    : remainingMs > refreshLead
      ? "Còn hiệu lực"
      : remaining > 0
        ? error && !canScan
          ? "Chưa có mã mới"
          : "Đang làm mới…"
        : pass
          ? "Đã hết hạn"
          : error
            ? "Chưa có mã"
            : "Đang tạo mã…";
  const today = liveAttendance ?? pass;
  const attendance =
    today?.status === "IN_SHIFT"
      ? "Đã ghi nhận giờ vào"
      : today?.status === "LEFT"
        ? "Đã ghi nhận giờ ra"
        : today
          ? "Chưa vào"
          : "Đang kiểm tra";
  const claims = pass ? tokenDetails(pass.token) : null;

  return (
    <div className="workflow-pass-content">
      <div className="workflow-person">
        <BadgeCheck aria-hidden="true" />
        <div>
          <h2>{employee.name}</h2>
          <p>
            {employee.id} · {groupLabel[employee.accessGroup]} · {employee.siteId}
          </p>
        </div>
      </div>
      <div className="workflow-today" role="status">
        <Clock3 aria-hidden="true" />
        <div>
          <h3>Ca hôm nay</h3>
          <strong>{attendance}</strong>
          {today?.lastEntry && (
            <p>
              Giờ vào {displayTime(today.lastEntry)}
              {today.status === "LEFT" && today.lastExit
                ? ` · Giờ ra ${displayTime(today.lastExit)}`
                : ""}
            </p>
          )}
        </div>
      </div>
      <div className="workflow-qr">
        {qr && canScan ? (
          <EmployeeQr key={qr.pass.token} payload={qr.payload} />
        ) : (
          <div className="workflow-qr-empty">
            <QrCode size={52} />
            <span>{status}</span>
          </div>
        )}
      </div>
      <div className="workflow-qr-status">
        <span className={revoked ? "workflow-pill danger" : "workflow-pill"}>{status}</span>
        {canScan && <strong>{remaining} giây</strong>}
      </div>
      {canScan && (
        <p className="workflow-qr-hint">
          Đưa mã vào máy quét tại cổng. Trạng thái ca sẽ tự cập nhật sau khi xác minh.
        </p>
      )}
      {error && (
        <div className="grid gap-2">
          <p className="workflow-error" role="alert">
            {error}
          </p>
          <button
            className="workflow-button secondary"
            onClick={() => {
              setQr(null);
              setError("");
            }}
          >
            Thử lại
          </button>
        </div>
      )}
      <details className="workflow-technical">
        <summary>Chi tiết kỹ thuật</summary>
        {claims ? (
          <dl className="workflow-kv">
            <dt>jti</dt>
            <dd>{claims.jti ?? "—"}</dd>
            <dt>cver</dt>
            <dd>{claims.cver ?? "—"}</dd>
            <dt>kid</dt>
            <dd>{claims.kid ?? "—"}</dd>
            <dt>TTL</dt>
            <dd>
              {claims.exp !== undefined && claims.iat !== undefined ? claims.exp - claims.iat : "—"}{" "}
              giây
            </dd>
          </dl>
        ) : (
          <p>Chưa có token.</p>
        )}
      </details>
    </div>
  );
}

export function EmployeePage() {
  return (
    <AppPage title="Mã QR của tôi">
      <RequireAccount roles={["EMPLOYEE", "MANAGER"]}>
        {(account) => <EmployeeProfile key={account.username} employeeId={account.employeeId} />}
      </RequireAccount>
    </AppPage>
  );
}

function EmployeeProfile({ employeeId }: { employeeId: string | null }) {
  const [employee, setEmployee] = useState<Employee | null>(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    workflow
      .employee(controller.signal)
      .then((person) => {
        if (controller.signal.aborted) return;
        if (person.id !== employeeId) {
          window.dispatchEvent(new Event("tmc-session-changed"));
          return;
        }
        setEmployee(person);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [employeeId, reload]);
  return (
    <section className="workflow-card workflow-pass">
      {error && (
        <div className="grid gap-2">
          <p role="alert" className="workflow-error">
            {error}
          </p>
          <button
            className="workflow-button"
            onClick={() => {
              setError("");
              setReload((value) => value + 1);
            }}
          >
            Thử lại
          </button>
        </div>
      )}
      {!error && !employee && <p role="status">Đang tải hồ sơ…</p>}
      {employee && <EmployeePass key={employee.id} employee={employee} />}
    </section>
  );
}
