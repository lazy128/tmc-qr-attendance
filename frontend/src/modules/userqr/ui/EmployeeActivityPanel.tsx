import { useEffect, useState, type FormEvent } from "react";
import { errorMessage } from "../../shared/lib/error-message";
import {
  accountApi,
  businessZone,
  siteName,
  workflow,
  type EmployeeActivity,
} from "../../shared/workflow";

const activityTime = new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "short",
  timeStyle: "short",
  timeZone: businessZone,
});

export function EmployeeActivityPanel({
  employeeId,
  onSessionsClosed,
}: {
  employeeId: string;
  onSessionsClosed: () => void;
}) {
  const [data, setData] = useState<EmployeeActivity | null>(null);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [closing, setClosing] = useState(false);
  const [savingDevice, setSavingDevice] = useState(false);
  const [deviceMessage, setDeviceMessage] = useState("");
  const [deviceError, setDeviceError] = useState("");

  useEffect(() => {
    const request = new AbortController();
    let timer = 0;
    async function refresh() {
      try {
        const activity = await workflow.activity(request.signal);
        if (request.signal.aborted) return;
        if (activity.employeeId !== employeeId) {
          window.dispatchEvent(new Event("tmc-session-changed"));
          return;
        }
        setData(activity);
        setError("");
      } catch (cause) {
        if (!request.signal.aborted) setError(errorMessage(cause));
      } finally {
        if (!request.signal.aborted) timer = window.setTimeout(refresh, 12_000);
      }
    }
    void refresh();
    return () => {
      request.abort();
      window.clearTimeout(timer);
    };
  }, [employeeId, revision]);

  async function closeOthers() {
    setClosing(true);
    setNotice("");
    setError("");
    try {
      const { closed } = await accountApi.closeOtherSessions();
      if (closed > 0) onSessionsClosed();
      setNotice(
        closed > 0
          ? `Đã đăng xuất ${closed} phiên khác. Mã QR đã được làm mới.`
          : "Phiên này là phiên duy nhất đang hoạt động.",
      );
      setRevision((value) => value + 1);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setClosing(false);
    }
  }

  async function saveDevice(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = String(new FormData(event.currentTarget).get("deviceName") ?? "").trim();
    if (savingDevice) return;
    setSavingDevice(true);
    setDeviceError("");
    setDeviceMessage("");
    try {
      await accountApi.nameDevice(name);
      setDeviceMessage("Đã lưu tên cho thiết bị này.");
      setRevision((value) => value + 1);
    } catch (cause) {
      setDeviceError(errorMessage(cause));
    } finally {
      setSavingDevice(false);
    }
  }

  return (
    <section className="workflow-card workflow-pass grid gap-3">
      <h2>Hoạt động tài khoản</h2>
      {error && (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      )}
      {!data && !error && <p>Đang tải hoạt động…</p>}
      {data && (
        <>
          {data.otherSessions > 0 && (
            <div className="workflow-activity-alert">
              <p>
                Có <strong>{data.otherSessions} phiên khác</strong> đang đăng nhập. Nếu bạn không
                nhận ra, hãy đăng xuất các phiên đó và đổi mật khẩu.
              </p>
              <button
                type="button"
                className="workflow-button secondary"
                disabled={closing}
                onClick={() => void closeOthers()}
              >
                Đăng xuất phiên khác
              </button>
            </div>
          )}
          {notice && <p aria-live="polite">{notice}</p>}
          <details className="workflow-technical">
            <summary>Lượt chấm công gần đây</summary>
            {data.scans.length ? (
              <ul className="workflow-activity-list">
                {data.scans.map((scan) => (
                  <li key={scan.eventId}>
                    <strong>
                      {scan.direction === "ENTRY" ? "Giờ vào" : "Giờ ra"} ·{" "}
                      {activityTime.format(new Date(scan.scannedAt))}
                    </strong>
                    <p>
                      {siteName(scan.siteId)} · {scan.stationName}
                      {scan.suspicious ? " · Cần đối chiếu" : ""}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <p>Chưa có lượt chấm công được ghi nhận.</p>
            )}
          </details>
          <details className="workflow-technical">
            <summary>Lịch sử đăng nhập</summary>
            <form className="grid gap-2 py-3" onSubmit={(event) => void saveDevice(event)}>
              <label className="workflow-field">
                Tên thiết bị này
                <input
                  key={data.currentDeviceName ?? ""}
                  name="deviceName"
                  defaultValue={data.currentDeviceName ?? ""}
                  placeholder="Ví dụ iPhone của An"
                  maxLength={60}
                  required
                />
              </label>
              <p>Đặt tên trên từng máy để nhận ra các lần đăng nhập từ trình duyệt đó.</p>
              <button
                type="submit"
                className="workflow-button secondary w-fit"
                disabled={savingDevice}
              >
                {savingDevice ? "Đang lưu…" : "Lưu tên thiết bị"}
              </button>
              {deviceError && <p role="alert">{deviceError}</p>}
              {deviceMessage && <p aria-live="polite">{deviceMessage}</p>}
            </form>
            {data.recentLogins.length ? (
              <ul className="workflow-activity-list">
                {data.recentLogins.map((login, position) => (
                  <li key={`${login.signedInAt}-${position}`}>
                    <strong>{login.deviceName ?? "Thiết bị chưa đặt tên"}</strong>
                    <p>
                      {login.deviceLabel} ·{" "}
                      {login.deviceCode ? `Mã ${login.deviceCode}` : "Phiên cũ"}
                    </p>
                    <p>{login.clientIp ? `IP ${login.clientIp}` : "IP chưa được ghi khi đăng nhập"}</p>
                    <p>
                      {activityTime.format(new Date(login.signedInAt))} ·{" "}
                      {login.current
                        ? "Phiên hiện tại"
                        : login.active
                          ? "Đang đăng nhập"
                          : "Đã kết thúc"}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <p>Chưa có đăng nhập mới kể từ khi bật lịch sử hoạt động.</p>
            )}
          </details>
        </>
      )}
    </section>
  );
}
