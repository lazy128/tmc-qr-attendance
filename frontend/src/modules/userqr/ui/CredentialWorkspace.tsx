import { ArrowRight, Download, KeyRound, QrCode, ShieldCheck } from "lucide-react";
import { QRCodeCanvas } from "qrcode.react";
import { useEffect, useRef, useState } from "react";
import { TextField } from "../../shared/ui/TextField";
import { errorMessage } from "../../shared/lib/error-message";
import { qrApi } from "../api/qr-api";
import type { IssuedQr } from "../model/issued-qr";

type CredentialWorkspaceProps = {
  credential: string;
  device: string;
  issuedQr: IssuedQr | null;
  busy: boolean;
  onCredential: (value: string) => void;
  onDevice: (value: string) => void;
  onIssue: (bearer: string, proof: string) => void;
  onRevoke: (bearer: string) => void;
  onOpenKiosk: () => void;
};

export function CredentialWorkspace({
  credential,
  device,
  issuedQr,
  busy,
  onCredential,
  onDevice,
  onIssue,
  onRevoke,
  onOpenKiosk,
}: CredentialWorkspaceProps) {
  const [now, setNow] = useState(() => Date.now());
  const [bearer, setBearer] = useState("");
  const [proof, setProof] = useState("");
  const [hr, setHr] = useState("");
  const [deviceCode, setDeviceCode] = useState<{
    credential: string;
    bearer: string;
    proof: string;
  } | null>(null);
  const [enrolling, setEnrolling] = useState(false);
  const [enrollError, setEnrollError] = useState("");
  const qrCanvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!issuedQr || issuedQr.used) return;
    const timer = window.setInterval(() => {
      const time = Date.now();
      setNow(time);
      if (time >= issuedQr.expiresAt) window.clearInterval(timer);
    }, 250);
    return () => window.clearInterval(timer);
  }, [issuedQr]);
  const remaining = issuedQr ? Math.max(0, Math.ceil((issuedQr.expiresAt - now) / 1000)) : 0;
  const active = issuedQr && !issuedQr.used && remaining > 0;
  const status = issuedQr?.used ? "Đã sử dụng" : issuedQr && !active ? "Đã hết hạn" : "Chưa có mã";
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  return (
    <div className="facility-credential-layout">
      <section className="facility-card facility-card--preview">
        <h2>Employee Digital ID</h2>
        <div className="facility-phone">
          <div className="facility-phone-inner">
            <div className="facility-phone-head">
              <span className="facility-avatar">TMC</span>
              <div>
                <strong>Thẻ nhân viên</strong>
                <small>TMC Employee Portal</small>
              </div>
            </div>
            <div className="facility-pass">
              <span className="facility-pass-caption">EMPLOYEE ACCESS CREDENTIAL</span>
              {active ? (
                <QRCodeCanvas
                  ref={qrCanvas}
                  value={issuedQr.token}
                  size={224}
                  level="M"
                  marginSize={4}
                  title="Mã QR truy cập"
                  role="img"
                />
              ) : (
                <div className="facility-pass-placeholder">
                  <QrCode size={52} aria-hidden="true" />
                  <span>{status}</span>
                </div>
              )}
              <strong className="break-all">{issuedQr?.credential ?? credential}</strong>
              <span className="facility-pass-status">
                {active ? "Còn " + remaining + " giây" : status}
              </span>
            </div>
          </div>
        </div>
      </section>
      <section className="facility-card facility-card--details">
        <h2>Credential details &amp; actions</h2>
        <div className="facility-field-grid">
          <TextField
            label="Credential ID"
            value={credential}
            onChange={(value) => {
              setDeviceCode(null);
              onCredential(value);
            }}
          />
          <TextField label="Thiết bị" value={device} onChange={onDevice} />
          <label className="demo-field">
            Mã đăng nhập nhân viên{" "}
            <input
              type="password"
              value={bearer}
              onChange={(event) => setBearer(event.target.value)}
            />
          </label>
          <label className="demo-field">
            Mã đăng ký thiết bị{" "}
            <input
              type="password"
              value={proof}
              onChange={(event) => setProof(event.target.value)}
            />
          </label>
        </div>
        <div className="facility-actions">
          <button
            type="button"
            className="facility-button facility-button--primary"
            disabled={busy || !credential.trim() || !bearer.trim() || !proof.trim()}
            onClick={() => {
              setNow(Date.now());
              onIssue(bearer, proof);
            }}
          >
            <QrCode size={16} /> {busy ? "Đang xử lý…" : "Cấp mã QR"}
          </button>
          <button type="button" className="facility-button" onClick={onOpenKiosk}>
            Mở Kiosk <ArrowRight size={15} />
          </button>
          {active && (
            <button
              type="button"
              className="facility-button"
              onClick={() => {
                if (!qrCanvas.current) return;
                const link = document.createElement("a");
                link.href = qrCanvas.current.toDataURL("image/png");
                link.download = "tmc-access-qr.png";
                link.click();
              }}
            >
              <Download size={15} /> Tải ảnh QR
            </button>
          )}
        </div>
        <div className="facility-note">
          <ShieldCheck size={18} />
          <p>
            Đưa mã vào camera ở Kiosk. Nếu thử trên cùng một máy, dùng nút{" "}
            <strong>Quét mã vừa cấp</strong> ở Kiosk.
          </p>
        </div>
        <div className="facility-credential-revoke">
          <label className="demo-field">
            Mã đăng nhập HR{" "}
            <input
              type="password"
              value={hr}
              onChange={(event) => {
                setDeviceCode(null);
                setHr(event.target.value);
              }}
            />
          </label>
          <button
            type="button"
            className="facility-button"
            disabled={busy || enrolling || !credential.trim() || !hr.trim()}
            onClick={async () => {
              setEnrolling(true);
              setDeviceCode(null);
              setEnrollError("");
              try {
                const result = await qrApi.enroll(credential.trim(), hr.trim());
                setDeviceCode({
                  credential: credential.trim(),
                  bearer: hr.trim(),
                  proof: result.deviceProof,
                });
              } catch (cause) {
                setEnrollError(errorMessage(cause));
              } finally {
                setEnrolling(false);
              }
            }}
          >
            Đăng ký thiết bị
          </button>
          {deviceCode?.credential === credential.trim() && deviceCode.bearer === hr.trim() && (
            <label className="demo-field">
              Mã đăng ký mới <input readOnly value={deviceCode.proof} />
            </label>
          )}
          {enrollError && (
            <p role="alert" className="facility-error">
              {enrollError}
            </p>
          )}
          {confirmRevoke ? (
            <>
              <p>
                Thu hồi credential <strong>{credential}</strong>? Các mã của credential này sẽ bị
                Edge từ chối.
              </p>
              <div className="facility-actions">
                <button
                  type="button"
                  className="facility-button"
                  onClick={() => setConfirmRevoke(false)}
                >
                  Hủy
                </button>
                <button
                  type="button"
                  className="facility-button facility-button--danger"
                  disabled={busy || !credential.trim() || !hr.trim()}
                  onClick={() => {
                    setConfirmRevoke(false);
                    onRevoke(hr);
                  }}
                >
                  Xác nhận thu hồi
                </button>
              </div>
            </>
          ) : (
            <button
              type="button"
              className="facility-button facility-button--danger"
              disabled={busy || !credential.trim()}
              onClick={() => setConfirmRevoke(true)}
            >
              <KeyRound size={15} /> Thu hồi credential
            </button>
          )}
        </div>
      </section>
    </div>
  );
}
