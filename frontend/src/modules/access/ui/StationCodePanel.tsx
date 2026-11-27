import { useEffect, useState } from "react";
import { errorMessage } from "../../shared/lib/error-message";
import { stationQrPayload } from "../../shared/lib/qr-link";
import { QrCanvas } from "../../shared/ui/QrCanvas";
import { workflow, type Direction, type Station } from "../../shared/workflow";

export function StationCodePanel({ station, close }: { station: Station; close: () => void }) {
  const [direction, setDirection] = useState<Direction>(station.gate.directions[0] ?? "ENTRY");
  const [payload, setPayload] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    let timer = 0;
    async function refresh() {
      try {
        const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(6500)]);
        const { code } = await workflow.stationCode(station.stationId, direction, signal);
        if (controller.signal.aborted) return;
        setPayload(stationQrPayload(code, window.location.origin));
        setError("");
      } catch (cause) {
        if (controller.signal.aborted) return;
        setPayload("");
        setError(errorMessage(cause));
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(refresh, 5_000);
      }
    }
    void refresh();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [station.stationId, direction]);

  return (
    <section className="workflow-card grid w-full max-w-lg gap-3">
      <h2>Mã QR tại cổng</h2>
      <p>{station.gate.name}</p>
      {station.gate.directions.length > 1 && (
        <label className="workflow-field">
          Hướng chấm công
          <select
            value={direction}
            onChange={(event) => {
              setPayload("");
              setError("");
              setDirection(event.target.value as Direction);
            }}
          >
            {station.gate.directions.map((value) => (
              <option key={value} value={value}>
                {value === "ENTRY" ? "Vào" : "Ra"}
              </option>
            ))}
          </select>
        </label>
      )}
      <p className="workflow-qr-hint">
        Nhân viên mở mục Quét QR chấm công trên điện thoại và quét mã này để ghi giờ{" "}
        {direction === "ENTRY" ? "vào" : "ra"}.
      </p>
      {payload ? (
        <div className="workflow-qr">
          <QrCanvas key={payload} payload={payload} label="Mã QR chấm công tại cổng" />
        </div>
      ) : error ? (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      ) : (
        <p role="status">Đang lấy mã QR tại cổng…</p>
      )}
      <button className="workflow-button secondary" type="button" onClick={close}>
        Đóng mã QR
      </button>
    </section>
  );
}
