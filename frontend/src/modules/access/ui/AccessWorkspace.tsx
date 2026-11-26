import { Camera, CircleCheck, CircleX, DoorOpen, ImageUp, ScanLine, WifiOff } from "lucide-react";
import { useState } from "react";
import type { IssuedQr } from "../../userqr/model/issued-qr";
import { readQrFile } from "../lib/read-qr";
import { stripQrPrefix } from "../lib/scan-token";
import type { VerifyResult } from "../model/types";
import { DecisionPanel } from "./DecisionPanel";
import { QrCamera } from "./QrCamera";
import { VerifyForm } from "./VerifyForm";

type AccessWorkspaceProps = {
  site: string;
  station: string;
  device: string;
  token: string;
  issuedQr: IssuedQr | null;
  direction: "ENTRY" | "EXIT";
  busy: boolean;
  result: VerifyResult | null;
  onSite: (value: string) => void;
  onStation: (value: string) => void;
  onDevice: (value: string) => void;
  onToken: (value: string) => void;
  onDirection: (value: "ENTRY" | "EXIT") => void;
  onVerify: (token: string) => void;
  onOpenQr: () => void;
};

export function AccessWorkspace(props: AccessWorkspaceProps) {
  const [cameraOpen, setCameraOpen] = useState(false);
  const [readingImage, setReadingImage] = useState(false);
  const [scanError, setScanError] = useState("");
  const waiting = props.busy || readingImage;
  const { result } = props;
  const mode = !result
    ? "idle"
    : result.command === "UNKNOWN"
      ? "pending"
      : result.decision === "DENY" || result.command === "FAILED"
        ? "denied"
        : "success";
  const display = {
    idle: [ScanLine, "Sẵn sàng quét QR"],
    pending: [WifiOff, "Đang đối soát lệnh cổng"],
    denied: [CircleX, "Cổng chưa mở"],
    success: [CircleCheck, "Đã cấp quyền truy cập"],
  } as const;
  const [Icon, message] = display[mode];

  function scan(code: string) {
    setCameraOpen(false);
    setScanError("");
    props.onVerify(code);
  }

  function verifyDecoded(code: string) {
    const token = stripQrPrefix(code);
    if (token.length > 4096 || !/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token)) {
      setCameraOpen(false);
      setScanError("Ảnh QR không chứa token truy cập TMC.");
      return;
    }
    scan(token);
  }

  async function scanImage(file: File) {
    setCameraOpen(false);
    setScanError("");
    setReadingImage(true);
    try {
      const code = await readQrFile(file);
      if (code) verifyDecoded(code);
      else setScanError("Chưa đọc được mã QR trong ảnh. Hãy thử ảnh nét hơn.");
    } catch {
      setScanError("Không mở được ảnh QR. Hãy chọn tệp PNG hoặc JPG.");
    } finally {
      setReadingImage(false);
    }
  }

  return (
    <div className="facility-access-layout">
      <section className="facility-kiosk">
        <div className="facility-kiosk-head">
          <DoorOpen size={21} aria-hidden="true" />
          <div>
            <strong>TMC SECURE ACCESS</strong>
            <small>
              {props.station} · {props.site}
            </small>
          </div>
          <span>Kiosk</span>
        </div>
        <div className={"facility-kiosk-screen facility-kiosk-screen--" + mode} aria-live="polite">
          {cameraOpen ? (
            <QrCamera
              onDecode={verifyDecoded}
              onFailure={(error) => {
                setCameraOpen(false);
                setScanError(error);
              }}
            />
          ) : (
            <>
              <Icon size={64} strokeWidth={1.25} aria-hidden="true" />
              <h2>{props.busy ? "Đang xác minh…" : message}</h2>
              <p>
                {result?.kioskMessage ??
                  "Đặt mã QR nhân viên vào vùng quét hoặc chọn ảnh mã từ thiết bị."}
              </p>
            </>
          )}
        </div>
        {scanError && (
          <p className="facility-kiosk-error" role="alert">
            {scanError}
          </p>
        )}
        <div className="facility-kiosk-actions">
          <button
            type="button"
            className="facility-button facility-button--white"
            disabled={waiting}
            onClick={() => {
              setScanError("");
              setCameraOpen(!cameraOpen);
            }}
          >
            <Camera size={16} /> {cameraOpen ? "Tắt camera" : "Bật camera"}
          </button>
          <label className="facility-button facility-button--kiosk-upload">
            <ImageUp size={16} /> {readingImage ? "Đang đọc ảnh…" : "Chọn ảnh QR"}
            <input
              type="file"
              accept="image/*"
              aria-label="Chọn ảnh QR"
              disabled={waiting}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) void scanImage(file);
              }}
            />
          </label>
          {props.issuedQr ? (
            <button
              type="button"
              className="facility-button facility-button--kiosk"
              disabled={waiting}
              onClick={() => {
                if (props.issuedQr) scan(props.issuedQr.token);
              }}
            >
              <ScanLine size={16} /> Quét mã vừa cấp
            </button>
          ) : (
            <button
              type="button"
              className="facility-button facility-button--kiosk"
              onClick={props.onOpenQr}
            >
              Cấp mã QR
            </button>
          )}
        </div>
      </section>
      <div className="facility-stack">
        <VerifyForm {...props} busy={waiting} onVerify={scan} />
        <DecisionPanel result={result} />
      </div>
    </div>
  );
}
