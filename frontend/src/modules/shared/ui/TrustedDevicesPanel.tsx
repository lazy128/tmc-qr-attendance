import { useEffect, useState } from "react";
import { errorMessage } from "../lib/error-message";
import { businessZone, workflow, type BrowserDevice, type DeviceList } from "../workflow";

const loginTime = new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "short",
  timeStyle: "short",
  timeZone: businessZone,
});

export function TrustedDevicesPanel({
  employeeId,
  className = "workflow-exports",
}: {
  employeeId?: string;
  className?: string;
}) {
  const [data, setData] = useState<DeviceList | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    workflow
      .devices(controller.signal, employeeId)
      .then((result) => {
        if (!controller.signal.aborted) setData(result);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [employeeId, revision]);

  function reload() {
    setError("");
    setRevision((value) => value + 1);
  }

  async function update(device: BrowserDevice) {
    setBusy(true);
    const controller = new AbortController();
    try {
      await workflow.changeDevice(device.reference, !device.trusted, employeeId);
      setData(await workflow.devices(controller.signal, employeeId));
      setError("");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={className}>
      <div className="grid w-full gap-3">
        <div className="workflow-actions">
          <h2>Thiết bị chấm công</h2>
          <button
            type="button"
            className="workflow-button secondary"
            disabled={busy}
            onClick={reload}
          >
            Cập nhật thiết bị
          </button>
        </div>
        {!data && !error && <p role="status">Đang tải thiết bị…</p>}
        {data && !data.canManage && (
          <p>
            Để quét QR tại cổng, hãy duyệt trình duyệt này từ một thiết bị tin cậy hoặc nhờ Nhân sự.
          </p>
        )}
        {data && data.devices.length === 0 && <p>Chưa có thiết bị đăng nhập.</p>}
        {data && data.devices.length > 0 && (
          <>
            <p>{data.devices.filter((device) => device.trusted).length}/3 thiết bị tin cậy</p>
            <ul className="grid gap-3">
              {data.devices.map((device) => (
                <li key={device.reference} className="grid gap-1 border-b pb-2">
                  <strong>{device.name || device.label || "Trình duyệt chưa đặt tên"}</strong>
                  <p>
                    Mã {device.code} · {device.trusted ? "Tin cậy" : "Chưa tin cậy"}
                    {device.current && " · Trình duyệt này"}
                  </p>
                  {device.lastLogin && (
                    <p>Lần đăng nhập gần nhất · {loginTime.format(new Date(device.lastLogin))}</p>
                  )}
                  {data.canManage && (
                    <button
                      type="button"
                      className="workflow-button secondary justify-self-start"
                      disabled={busy}
                      aria-label={`${device.trusted ? "Gỡ tin cậy" : "Duyệt thiết bị"} ${device.code}`}
                      onClick={() => void update(device)}
                    >
                      {device.trusted ? "Gỡ tin cậy" : "Duyệt thiết bị"}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
        {error && (
          <p className="workflow-error" role="alert">
            {error}
          </p>
        )}
      </div>
    </section>
  );
}
