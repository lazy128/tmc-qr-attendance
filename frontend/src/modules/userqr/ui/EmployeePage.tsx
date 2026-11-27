import { useEffect, useEffectEvent, useState } from "react";
import { BadgeCheck, Clock3, QrCode } from "lucide-react";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import {
  checkInCodeFromQr,
  employeeQrPayload,
  guardQrPayload,
  type CheckInCode,
} from "../../shared/lib/qr-link";
import { QrCanvas } from "../../shared/ui/QrCanvas";
import { AppPage } from "../../shared/ui/AppPage";
import { RequireAccount } from "../../shared/ui/Auth";
import { EmployeeActivityPanel } from "./EmployeeActivityPanel";
import { EmployeeCheckIn } from "./EmployeeCheckIn";
import {
  displayTime,
  groupLabel,
  siteName,
  workflow,
  type AttendanceStatus,
  type Employee,
  type Pass,
} from "../../shared/workflow";

const phoneRefreshLeadMs = 3_000;
const kioskRefreshLeadMs = 8_000;
type QrMode = "ENTRY" | "EXIT" | "GUARD";

function qrLifetimeMs(token: string): number | undefined {
  try {
    const [, payload = ""] = token.split(".");
    const { iat, exp } = JSON.parse(atob(payload.replaceAll("-", "+").replaceAll("_", "/"))) as {
      iat?: number;
      exp?: number;
    };
    if (typeof iat === "number" && typeof exp === "number") return (exp - iat) * 1000;
  } catch {
    // Use the API expiry if timing claims are unavailable.
  }
  return undefined;
}

function EmployeePass({
  employee,
  confirmationView,
  onFinishedChange,
}: {
  employee: Employee;
  confirmationView: boolean;
  onFinishedChange: (finished: boolean) => void;
}) {
  const [mode, setMode] = useState<QrMode | null>(null);
  const [qr, setQr] = useState<{ pass: Pass; deadline: number } | null>(null);
  const [liveAttendance, setLiveAttendance] = useState<AttendanceStatus | null>(null);
  const [now, setNow] = useState(0);
  const [error, setError] = useState("");
  const [revoked, setRevoked] = useState(false);
  const pass = qr?.pass;
  const today = liveAttendance ?? pass;
  const confirmed = today?.status === "IN_SHIFT";
  const left = today?.status === "LEFT";
  const currentMode = mode ?? (confirmed || left ? null : "ENTRY");
  const guardMode = currentMode === "GUARD";
  const showQr = !confirmationView && !left && currentMode !== null;

  function openQr(next: QrMode | null) {
    setQr(null);
    setError("");
    setRevoked(false);
    setMode(next);
  }

  const updateAttendance = useEffectEvent((latest: AttendanceStatus) => {
    if (today && (today.workDate !== latest.workDate || today.status !== latest.status))
      openQr(null);
    setLiveAttendance(latest);
    onFinishedChange(latest.status === "LEFT");
  });
  const fetchPass = useEffectEvent(async (signal: AbortSignal) => {
    const requestedAt = performance.now();
    try {
      const direction =
        currentMode === "EXIT" ? "EXIT" : currentMode === "ENTRY" ? "ENTRY" : undefined;
      const issued = await workflow.pass(signal, direction);
      if (signal.aborted) return;
      if (issued.employeeId !== employee.id) {
        setQr(null);
        window.dispatchEvent(new Event("tmc-session-changed"));
        return;
      }
      const receivedAt = performance.now();
      const lifetime = qrLifetimeMs(issued.token);
      // The signed timestamps are rounded down to seconds. Budget for that and the request time.
      const deadline =
        lifetime !== undefined
          ? requestedAt + lifetime - 1000
          : receivedAt + (Date.parse(issued.expiresAt) - Date.now());
      setNow(receivedAt);
      setQr({
        pass: issued,
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
  const payload = pass
    ? (guardMode ? guardQrPayload : employeeQrPayload)(pass.token, window.location.origin)
    : "";
  const phoneLink = payload.startsWith("http");
  const refreshLead = phoneLink ? phoneRefreshLeadMs : kioskRefreshLeadMs;
  useEffect(() => {
    if (!showQr || revoked || error) return;
    const controller = new AbortController();
    const delay =
      deadline === undefined ? 0 : Math.max(1000, deadline - performance.now() - refreshLead);
    const timer = window.setTimeout(() => void fetchPass(controller.signal), delay);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [employee.id, deadline, refreshLead, error, revoked, showQr, currentMode]);
  useEffect(() => {
    if (deadline === undefined) return;
    const timer = window.setInterval(() => setNow(performance.now()), 250);
    return () => window.clearInterval(timer);
  }, [deadline]);
  useEffect(() => {
    const controller = new AbortController();
    let timer = window.setTimeout(refreshAttendance, 0);
    async function refreshAttendance() {
      try {
        const latest = await workflow.attendance(controller.signal);
        if (controller.signal.aborted) return;
        if (latest.employeeId !== employee.id) {
          setQr(null);
          window.dispatchEvent(new Event("tmc-session-changed"));
          return;
        }
        updateAttendance(latest);
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
  const remainingMs = Math.max(0, (deadline ?? now) - now);
  const remaining = Math.ceil(remainingMs / 1000);
  const canScan = remainingMs > 0;
  const countdown =
    remaining >= 60
      ? `${Math.floor(remaining / 60)} phút ${remaining % 60} giây`
      : `${remaining} giây`;
  const status = revoked
    ? "Đã thu hồi"
    : remainingMs > refreshLead
      ? "Còn hiệu lực"
      : remaining > 0
        ? "Đang làm mới…"
        : pass
          ? "Đã hết hạn"
          : error
            ? "Chưa có mã"
            : "Đang tạo mã…";
  const attendance =
    today?.status === "IN_SHIFT"
      ? "Đã ghi nhận giờ vào"
      : today?.status === "LEFT"
        ? "Đã ghi nhận giờ ra"
        : today
          ? "Chưa vào"
          : "Đang kiểm tra";
  return (
    <div className="workflow-pass-content">
      <div className="workflow-person">
        <BadgeCheck aria-hidden="true" />
        <div>
          <h2>{employee.name}</h2>
          <p>
            {employee.id} · {groupLabel[employee.accessGroup]}
          </p>
          <p>Chi nhánh được phân công · {siteName(employee.siteId)}</p>
        </div>
      </div>
      <div className="workflow-today" role="status">
        <Clock3 aria-hidden="true" />
        <div>
          <h3>Ca hôm nay</h3>
          {today?.workDate && <p>{today.workDate.split("-").reverse().join("/")}</p>}
          <strong>{attendance}</strong>
          {today?.lastEntry && (
            <p>
              Giờ vào {displayTime(today.lastEntry)}
              {today.status === "LEFT" && today.lastExit
                ? ` · Giờ ra ${displayTime(today.lastExit)}`
                : ""}
            </p>
          )}
          {left && <p>Đã kết thúc ngày công.</p>}
        </div>
      </div>
      {!confirmationView && confirmed && (
        <div className="workflow-today">
          <BadgeCheck aria-hidden="true" />
          <strong>Đã xác nhận chấm công</strong>
        </div>
      )}
      {!confirmationView && !left && (
        <div className="workflow-actions">
          {confirmed && (
            <button
              type="button"
              className="workflow-button"
              aria-expanded={currentMode === "EXIT"}
              onClick={() => openQr(currentMode === "EXIT" ? null : "EXIT")}
            >
              {currentMode === "EXIT" ? "Ẩn QR giờ ra" : "Mở QR giờ ra"}
            </button>
          )}
          <button
            type="button"
            className="workflow-button secondary"
            aria-expanded={guardMode}
            onClick={() => openQr(guardMode ? null : "GUARD")}
          >
            {guardMode
              ? confirmed
                ? "Ẩn QR cho bảo vệ"
                : "Về QR cá nhân"
              : "Mở QR cho bảo vệ quét"}
          </button>
        </div>
      )}
      {showQr && (
        <>
          <h3>
            {guardMode
              ? "Mã QR cho bảo vệ"
              : currentMode === "EXIT"
                ? "Mã QR giờ ra"
                : "Mã QR của tôi"}
          </h3>
          <div className="workflow-qr">
            {pass && canScan ? (
              <QrCanvas key={pass.token} payload={payload} label="Mã QR truy cập" />
            ) : (
              <div className="workflow-qr-empty">
                <QrCode size={52} />
                <span>{status}</span>
              </div>
            )}
          </div>
          <div className="workflow-qr-status">
            <span className={revoked ? "workflow-pill danger" : "workflow-pill"}>{status}</span>
            {canScan && <strong>{countdown}</strong>}
          </div>
          {canScan && (
            <p className="workflow-qr-hint">
              {guardMode
                ? `Đưa mã cho bảo vệ tại ${siteName(employee.siteId)}. Trạm được giao sẽ ghi giờ vào hoặc ra.`
                : phoneLink
                  ? `Quét mã bằng điện thoại đã đăng nhập cùng tài khoản để ghi giờ ${currentMode === "EXIT" ? "ra" : "vào"}.`
                  : "Trên điện thoại, dùng mục Quét QR chấm công để đọc mã này."}
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
        </>
      )}
    </div>
  );
}

export function EmployeePage() {
  const [linkedCode, setLinkedCode] = useState<CheckInCode | null>(null);
  const [confirmationView, setConfirmationView] = useState(false);
  useEffect(() => {
    function readLink() {
      const code = checkInCodeFromQr(window.location.href);
      if (!code) return;
      setLinkedCode(code);
      setConfirmationView(true);
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
    <AppPage title="Chấm công cá nhân">
      <RequireAccount
        roles={["EMPLOYEE", "MANAGER"]}
        loginHint={
          linkedCode?.kind === "personal"
            ? "Đăng nhập bằng cùng tài khoản nhân viên đang mở mã QR trên máy tính."
            : linkedCode
              ? "Đăng nhập tài khoản nhân viên để xác nhận chấm công tại cổng."
              : ""
        }
      >
        {(account) => (
          <EmployeeProfile
            key={account.username}
            employeeId={account.employeeId}
            linkedCode={linkedCode}
            confirmationView={confirmationView}
            clearLink={() => setLinkedCode(null)}
          />
        )}
      </RequireAccount>
    </AppPage>
  );
}

function EmployeeProfile({
  employeeId,
  linkedCode,
  confirmationView,
  clearLink,
}: {
  employeeId: string | null;
  linkedCode: CheckInCode | null;
  confirmationView: boolean;
  clearLink: () => void;
}) {
  const [employee, setEmployee] = useState<Employee | null>(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [qrRevision, setQrRevision] = useState(0);
  const [finished, setFinished] = useState<boolean | null>(null);
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
    <div className="grid gap-4">
      {employee && (finished === false || confirmationView) && (
        <EmployeeCheckIn
          linkedCode={linkedCode}
          confirmationView={confirmationView}
          finished={finished === true}
          clearLink={clearLink}
          onRecorded={() => setQrRevision((revision) => revision + 1)}
        />
      )}
      <section className={`workflow-card workflow-pass${confirmationView ? "" : " order-first"}`}>
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
        {employee && (
          <EmployeePass
            key={`${employee.id}-${qrRevision}`}
            employee={employee}
            confirmationView={confirmationView}
            onFinishedChange={setFinished}
          />
        )}
      </section>
      {employee && (
        <EmployeeActivityPanel
          employeeId={employee.id}
          onSessionsClosed={() => setQrRevision((revision) => revision + 1)}
        />
      )}
    </div>
  );
}
