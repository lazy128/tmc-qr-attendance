import { useEffect, useState, type FormEvent } from "react";
import { Download, RefreshCw } from "lucide-react";
import { GateIncidentQueue } from "../../access/ui/GateIncidentQueue";
import { errorMessage } from "../../shared/lib/error-message";
import { AppPage } from "../../shared/ui/AppPage";
import { TrustedDevicesPanel } from "../../shared/ui/TrustedDevicesPanel";
import { RequireAccount } from "../../shared/ui/Auth";
import {
  businessZone,
  canManageTimesheets,
  displayTime,
  siteName,
  workflow,
  workplaceSiteNames,
  type Role,
  type ScanDetail,
  type Timesheet,
} from "../../shared/workflow";

const labels = {
  COMPLETE: "Đủ giờ",
  MISSING_EXIT: "Thiếu giờ ra",
  DUPLICATE: "Trùng lượt",
  REVIEW: "Cần HR xem",
};
function currentMonth() {
  const parts = new Intl.DateTimeFormat("en", {
    year: "numeric",
    month: "2-digit",
    timeZone: businessZone,
  }).formatToParts();
  return `${parts.find((part) => part.type === "year")?.value}-${parts.find((part) => part.type === "month")?.value}`;
}

function dayLabel(value: string) {
  return value.split("-").reverse().join("/");
}

function hours(minutes: number) {
  return `${Math.floor(minutes / 60)} giờ ${String(minutes % 60).padStart(2, "0")} phút`;
}

function TechnicalRow({ row }: { row: Timesheet }) {
  const [scans, setScans] = useState<ScanDetail[] | null>(null);
  const [error, setError] = useState("");
  return (
    <details
      className="workflow-technical row-details"
      onToggle={(event) => {
        if (event.currentTarget.open && scans === null) {
          setError("");
          void workflow
            .details(row.employeeId, row.workDate)
            .then(setScans)
            .catch((cause) => setError(errorMessage(cause)));
        }
      }}
    >
      <summary>Lượt chấm công</summary>
      {error ? (
        <p role="alert">{error}</p>
      ) : scans ? (
        <ul>
          {scans.map((scan) => (
            <li key={scan.eventId}>
              <strong>
                {displayTime(scan.scannedAt)} · {scan.direction === "ENTRY" ? "Vào" : "Ra"} ·{" "}
                {scan.evidence === "SELF_REPORTED"
                  ? "Tự khai · Chờ Nhân sự xem"
                  : (scan.evidence ?? scan.command)}
              </strong>
              <code>{scan.eventId}</code>
            </li>
          ))}
        </ul>
      ) : (
        <p>Đang tải…</p>
      )}
    </details>
  );
}

export function TimesheetPage() {
  return (
    <AppPage title="Bảng công theo ngày">
      <RequireAccount roles={["EMPLOYEE", "MANAGER", "HR", "ADMIN"]}>
        {(account) => (
          <Timesheet key={account.username} role={account.role} assignedSite={account.siteId} />
        )}
      </RequireAccount>
    </AppPage>
  );
}

function Timesheet({ role, assignedSite }: { role: Role; assignedSite: string | null }) {
  const [month, setMonth] = useState(currentMonth);
  const [site, setSite] = useState("");
  const [filter, setFilter] = useState("");
  const [rows, setRows] = useState<Timesheet[]>([]);
  const [roster, setRoster] = useState<{ employeeId: string; employeeName: string }[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [operation, setOperation] = useState(false);
  const [reload, setReload] = useState(0);
  const [edit, setEdit] = useState<Timesheet | null>(null);
  const canExport = canManageTimesheets(role);
  useEffect(() => {
    if (!month) return;
    const controller = new AbortController();
    async function load() {
      setLoading(true);
      setError("");
      try {
        const [result, people] = await Promise.all([
          workflow.rows(month, filter, controller.signal, site),
          role === "EMPLOYEE" ? [] : workflow.roster(controller.signal, site),
        ]);
        if (controller.signal.aborted) return;
        setRows(result);
        setRoster(people);
      } catch (cause) {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    void load();
    return () => controller.abort();
  }, [role, month, filter, reload, site]);

  async function operate(action: () => Promise<void>) {
    setOperation(true);
    setError("");
    try {
      await action();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setOperation(false);
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!edit) return;
    const data = new FormData(event.currentTarget);
    const exit = new Date(`${String(data.get("exit"))}:00+07:00`).toISOString();
    const reason = String(data.get("reason")).trim();
    void operate(async () => {
      await workflow.correct(edit.employeeId, edit.workDate, exit, reason);
      setEdit(null);
      setReload((value) => value + 1);
    });
  }

  function download(type: "csv" | "xlsx") {
    void operate(async () => {
      const blob = await workflow.export(month, type, site, filter);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `timesheet-${month}${site ? `-${site}` : ""}.${type}`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  return (
    <section className="workflow-card workflow-attendance">
      <div className="workflow-filters">
        <label className="workflow-field">
          Tháng
          <input
            type="month"
            value={month}
            required
            disabled={operation}
            onChange={(event) => {
              setRows([]);
              setEdit(null);
              setMonth(event.target.value);
            }}
          />
        </label>
        {canExport && (
          <label className="workflow-field">
            Nhà máy
            <select
              value={site}
              disabled={operation}
              onChange={(event) => {
                setSite(event.currentTarget.value);
                setLoading(Boolean(month));
                setRows([]);
                setRoster([]);
                setFilter("");
                setEdit(null);
              }}
            >
              <option value="">Cả hai nhà máy</option>
              {Object.entries(workplaceSiteNames).map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </label>
        )}
        {!canExport && assignedSite && (
          <div className="workflow-field">
            <span>Chi nhánh được phân công</span>
            <strong>{siteName(assignedSite)}</strong>
          </div>
        )}
        {role !== "EMPLOYEE" && (
          <label className="workflow-field">
            Nhân viên
            <select
              value={filter}
              disabled={operation}
              onChange={(event) => {
                setRows([]);
                setEdit(null);
                setFilter(event.target.value);
              }}
            >
              <option value="">Tất cả trong phạm vi</option>
              {roster.map((item) => (
                <option key={item.employeeId} value={item.employeeId}>
                  {item.employeeName} · {item.employeeId}
                </option>
              ))}
            </select>
          </label>
        )}
        <button
          className="workflow-button secondary self-end"
          disabled={!month || loading || operation}
          onClick={() => setReload((value) => value + 1)}
        >
          <RefreshCw size={16} /> Làm mới
        </button>
      </div>
      {canExport && (
        <div className="workflow-exports">
          <div>
            <strong>Xuất bảng công</strong>
            <p>Tệp tải xuống áp dụng bộ lọc đang chọn.</p>
          </div>
          <div className="workflow-actions">
            <button
              className="workflow-button secondary"
              disabled={loading || operation || !month}
              onClick={() => download("xlsx")}
            >
              <Download size={16} /> Tải bảng tính (.xlsx)
            </button>
            <button
              className="workflow-button"
              disabled={loading || operation || !month}
              onClick={() => download("csv")}
            >
              <Download size={16} /> Xuất CSV cho lương
            </button>
          </div>
        </div>
      )}
      {canExport && <GateIncidentQueue />}
      {canExport && filter && <TrustedDevicesPanel key={filter} employeeId={filter} />}
      {error && (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      )}
      {edit && (
        <form key={edit.employeeId + edit.workDate} className="workflow-correction" onSubmit={save}>
          <h2>
            Bổ sung giờ ra cho {edit.employeeName} · {dayLabel(edit.workDate)}
          </h2>
          <label className="workflow-field">
            Giờ ra (giờ Việt Nam)
            <input
              name="exit"
              type="datetime-local"
              required
              defaultValue={`${edit.workDate}T17:00`}
            />
          </label>
          <label className="workflow-field">
            Lý do
            <input name="reason" required maxLength={300} />
          </label>
          <div className="workflow-actions">
            <button className="workflow-button" disabled={operation}>
              Lưu giờ ra
            </button>
            <button
              type="button"
              className="workflow-button secondary"
              disabled={operation}
              onClick={() => setEdit(null)}
            >
              Hủy
            </button>
          </div>
        </form>
      )}
      <div className="workflow-table-wrap">
        <table className="workflow-table">
          <thead>
            <tr>
              <th>Nhân viên</th>
              <th>Ngày</th>
              <th>Giờ vào</th>
              <th>Giờ ra</th>
              <th>Tổng giờ</th>
              <th>Trạng thái</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={6}>Đang tải bảng công…</td>
              </tr>
            ) : rows.length ? (
              rows.map((row) => (
                <tr
                  key={row.employeeId + row.workDate}
                  className={row.reviewFlag ? "exception" : ""}
                >
                  <td>
                    <strong>{row.employeeName}</strong>
                    <small>
                      {row.employeeId} · {siteName(row.siteId)}
                    </small>
                  </td>
                  <td>{dayLabel(row.workDate)}</td>
                  <td>{displayTime(row.firstIn)}</td>
                  <td>{displayTime(row.lastOut)}</td>
                  <td>{hours(row.workedMinutes)}</td>
                  <td>
                    <span className={`workflow-pill ${row.status !== "COMPLETE" ? "warning" : ""}`}>
                      {labels[row.status]}
                    </span>
                    {row.reviewFlag && row.status === "COMPLETE" && <small>Cần HR xác nhận</small>}
                    {canExport && row.status === "MISSING_EXIT" && (
                      <button
                        className="workflow-text-button"
                        disabled={operation}
                        onClick={() => setEdit(row)}
                      >
                        Sửa công
                      </button>
                    )}
                    <TechnicalRow row={row} />
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={6}>
                  {month ? "Chưa có dữ liệu công cho bộ lọc này." : "Chọn tháng để xem bảng công."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
