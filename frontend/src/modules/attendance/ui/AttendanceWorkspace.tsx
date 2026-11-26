import { useState } from "react";
import { Download, RefreshCw } from "lucide-react";
import type { AttendanceRecord } from "../model/types";

const localTime = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  dateStyle: "short",
  timeStyle: "medium",
});

type AttendanceWorkspaceProps = {
  rows: AttendanceRecord[];
  busy: boolean;
  token: string;
  onToken: (token: string) => void;
  onRefresh: () => void;
  onExport: (month: string) => void;
};

export function AttendanceWorkspace({
  rows,
  busy,
  token,
  onToken,
  onRefresh,
  onExport,
}: AttendanceWorkspaceProps) {
  const [month, setMonth] = useState("");
  return (
    <section className="facility-card">
      <div className="space-y-4">
        <h2>Sổ chấm công</h2>
        <div className="flex flex-wrap items-end gap-3">
          <label className="grid gap-1 text-sm">
            Token truy cập
            <input
              type="password"
              value={token}
              onChange={(event) => onToken(event.target.value)}
              disabled={busy}
              autoComplete="off"
              className="rounded-lg border px-3 py-2"
            />
          </label>
          <button
            type="button"
            disabled={busy || !token.trim()}
            onClick={onRefresh}
            className="facility-button facility-button--primary"
          >
            <RefreshCw size={15} /> Tải bản ghi
          </button>
          <label className="grid gap-1 text-sm">
            Tháng báo cáo
            <input
              type="month"
              value={month}
              onChange={(event) => setMonth(event.target.value)}
              className="rounded-lg border px-3 py-2"
            />
          </label>
          <button
            type="button"
            disabled={busy || !token.trim() || !month}
            onClick={() => onExport(month)}
            className="facility-button"
          >
            <Download size={15} /> Xuất CSV HR
          </button>
        </div>
      </div>
      <div className="facility-table-wrap">
        <table>
          <thead>
            <tr>
              {[
                "Event",
                "Nhân viên",
                "Site / Trạm",
                "Giờ quét (VN)",
                "Chiều",
                "Lệnh cổng",
                "Chứng cứ",
                "Chấm công",
              ].map((heading) => (
                <th key={heading} scope="col">
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.eventId}>
                <td className="max-w-32 truncate font-mono" title={row.eventId}>
                  {row.eventId}
                </td>
                <td>{row.employeeId}</td>
                <td>
                  {row.siteId} · {row.stationId}
                </td>
                <td>{localTime.format(new Date(row.scannedAt))}</td>
                <td>{row.direction === "ENTRY" ? "Vào" : "Ra"}</td>
                <td>{row.command}</td>
                <td>{row.evidence ?? "—"}</td>
                <td>
                  {row.eligible ? (row.hrReview ? "HR cần rà soát" : "Đủ điều kiện") : "Đang chờ"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && (
          <p className="p-7 text-center text-sm text-slate-500">Chưa có bản ghi để hiển thị.</p>
        )}
      </div>
    </section>
  );
}
