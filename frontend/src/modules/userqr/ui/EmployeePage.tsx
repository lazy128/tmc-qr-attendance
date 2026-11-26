import { useEffect, useEffectEvent, useRef, useState } from "react";
import QRCode from "qrcode";
import { ApiRequestError } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { DemoPage } from "../../shared/ui/DemoPage";
import { qrApi } from "../api/qr-api";

type Session = { credential: string; bearer: string; proof: string };
type Pass = { token: string; expiresAt: number };

function EmployeeQr({ token }: { token: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!canvas.current) return;
    let active = true;
    QRCode.toCanvas(canvas.current, `tmc1:${token}`, {
      width: 288,
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

export function EmployeePage() {
  const [session, setSession] = useState<Session | null>(null);
  const [pass, setPass] = useState<Pass | null>(null);
  const [now, setNow] = useState(0);
  const [error, setError] = useState("");
  const [revoked, setRevoked] = useState(false);

  const refreshPass = useEffectEvent(async (current: Session, signal: AbortSignal) => {
    try {
      const issued = await qrApi.issue(current.credential, current.bearer, current.proof, signal);
      if (signal.aborted) return;
      const expiresAt = Date.parse(issued.expiresAt);
      if (!Number.isFinite(expiresAt)) throw new Error("Edge trả về thời hạn QR không hợp lệ.");
      setNow(Date.now());
      setPass({ token: issued.token, expiresAt });
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
    if (!session || revoked || error) return;
    const controller = new AbortController();
    const delay = expiresAt === undefined ? 0 : Math.max(1000, expiresAt - Date.now() - 8000);
    const timer = window.setTimeout(() => void refreshPass(session, controller.signal), delay);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [session, expiresAt, error, revoked]);

  useEffect(() => {
    if (!pass) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [pass]);

  const remaining = pass ? Math.max(0, Math.ceil((pass.expiresAt - now) / 1000)) : 0;

  return (
    <DemoPage title="Mã QR nhân viên">
      <section className="facility-card mx-auto grid max-w-sm gap-4">
        {!session ? (
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              const credential = String(data.get("credential")).trim();
              const bearer = String(data.get("bearer")).trim();
              const proof = String(data.get("proof")).trim();
              if (credential && bearer && proof) setSession({ credential, bearer, proof });
            }}
          >
            <h2>Đăng nhập demo</h2>
            <label className="demo-field">
              Credential ID <input name="credential" defaultValue="demo-credential" required />
            </label>
            <label className="demo-field">
              Mã đăng nhập nhân viên <input name="bearer" type="password" required />
            </label>
            <label className="demo-field">
              Mã đăng ký do HR cấp <input name="proof" type="password" required />
            </label>
            <button className="facility-button facility-button--primary">Hiển thị QR</button>
          </form>
        ) : (
          <>
            <h2>{session.credential}</h2>
            {pass && remaining > 0 && (
              <div className="mx-auto w-full max-w-72">
                <EmployeeQr key={pass.token} token={pass.token} />
              </div>
            )}
            <p className="text-center font-semibold">
              {revoked
                ? "REVOKED"
                : pass
                  ? remaining > 0
                    ? `Còn ${remaining} giây`
                    : "EXPIRED"
                  : error
                    ? "Chưa có mã"
                    : "Đang cấp mã…"}
            </p>
            {error && (
              <>
                <p className="facility-error" role="alert">
                  {error}
                </p>
                <button
                  className="facility-button"
                  onClick={() => {
                    setPass(null);
                    setError("");
                  }}
                >
                  Thử lại
                </button>
              </>
            )}
            <button
              className="facility-button"
              onClick={() => {
                setSession(null);
                setPass(null);
                setError("");
                setRevoked(false);
              }}
            >
              Đăng xuất
            </button>
          </>
        )}
      </section>
    </DemoPage>
  );
}
