import { useEffect, useEffectEvent, useRef, useState } from "react";
import { BadgeCheck, Clock3, QrCode } from "lucide-react";
import QRCode from "qrcode";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { DemoPage } from "../../shared/ui/DemoPage";
import { displayTime, groupLabel, workflow, type Employee, type Pass } from "../../shared/workflow";

function EmployeeQr({ token }: { token: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!canvas.current) return;
    let active = true;
    QRCode.toCanvas(canvas.current, `tmc1:${token}`, {
      width: 320,
      margin: 4,
      errorCorrectionLevel: "M",
    }).catch(() => {
      if (active) setFailed(true);
    });
    return () => {
      active = false;
    };
  }, [token]);
  if (failed) return <p role="alert">Không thể hiển thị mã QR.</p>;
  return <canvas ref={canvas} role="img" aria-label="Mã QR truy cập" className="h-auto w-full" />;
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
  const [pass, setPass] = useState<Pass | null>(null);
  const [now, setNow] = useState(0);
  const [error, setError] = useState("");
  const [revoked, setRevoked] = useState(false);
  const fetchPass = useEffectEvent(async (id: string, signal: AbortSignal) => {
    try {
      const issued = await workflow.pass(id, signal);
      if (signal.aborted) return;
      setNow(Date.now());
      setPass(issued);
    } catch (cause) {
      if (signal.aborted) return;
      if (cause instanceof ApiRequestError && cause.code === "REVOKED") {
        setPass(null);
        setRevoked(true);
      } else {
        setError(errorMessage(cause));
      }
    }
  });
  const expiresAt = pass?.expiresAt;
  useEffect(() => {
    if (revoked || error) return;
    const controller = new AbortController();
    const delay = expiresAt ? Math.max(1000, Date.parse(expiresAt) - Date.now() - 8000) : 0;
    const timer = window.setTimeout(() => void fetchPass(employee.id, controller.signal), delay);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [employee.id, expiresAt, error, revoked]);
  useEffect(() => {
    if (!pass) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [pass]);
  const remaining = expiresAt ? Math.max(0, Math.ceil((Date.parse(expiresAt) - now) / 1000)) : 0;
  const status = revoked
    ? "Đã thu hồi"
    : remaining > 8
      ? "Còn hiệu lực"
      : remaining > 0
        ? "Sắp làm mới"
        : pass
          ? "Đã hết hạn"
          : error
            ? "Chưa có mã"
            : "Đang tạo mã…";
  const attendance =
    pass?.status === "IN_SHIFT"
      ? "Đang trong ca"
      : pass?.status === "LEFT"
        ? "Đã ra"
        : pass
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
            {employee.id} · {groupLabel[employee.accessGroup]} ·{" "}
            {employee.siteId === "SITE-LA" ? "Long An" : "Bình Dương"}
          </p>
        </div>
      </div>
      <div className="workflow-qr">
        {pass && remaining > 0 ? (
          <EmployeeQr key={pass.token} token={pass.token} />
        ) : (
          <div className="workflow-qr-empty">
            <QrCode size={52} />
            <span>{status}</span>
          </div>
        )}
      </div>
      <div className="workflow-qr-status">
        <span className={revoked ? "workflow-pill danger" : "workflow-pill"}>{status}</span>
        {remaining > 0 && <strong>{remaining} giây</strong>}
      </div>
      {error && (
        <div className="grid gap-2">
          <p className="workflow-error" role="alert">
            {error}
          </p>
          <button
            className="workflow-button secondary"
            onClick={() => {
              setPass(null);
              setError("");
            }}
          >
            Thử lại
          </button>
        </div>
      )}
      <div className="workflow-today">
        <Clock3 aria-hidden="true" />
        <div>
          <h3>Ca hôm nay</h3>
          <strong>{attendance}</strong>
          {pass?.lastEntry && (
            <p>
              Giờ vào {displayTime(pass.lastEntry)}
              {pass.status === "LEFT" && pass.lastExit ? ` · Giờ ra ${displayTime(pass.lastExit)}` : ""}
            </p>
          )}
        </div>
      </div>
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
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [selection, setSelection] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    workflow
      .employees(controller.signal)
      .then((items) => {
        if (!controller.signal.aborted) setEmployees(items);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, []);
  const employee = employees.find((item) => item.id === selection) ?? employees[0];
  return (
    <DemoPage title="Mã QR nhân viên">
      <section className="workflow-card workflow-pass">
        <label className="workflow-field">
          Tài khoản demo
          <select
            value={employee?.id ?? ""}
            disabled={!employee}
            onChange={(event) => setSelection(event.target.value)}
          >
            {!employee && <option value="">Đang tải nhân viên…</option>}
            {employees.map((item) => (
              <option value={item.id} key={item.id}>
                {item.name} · {item.id}
              </option>
            ))}
          </select>
        </label>
        {error && (
          <p role="alert" className="workflow-error">
            Không tải được tài khoản demo. {error}
          </p>
        )}
        {employee && <EmployeePass key={employee.id} employee={employee} />}
      </section>
    </DemoPage>
  );
}
