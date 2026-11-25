import { useState } from "react";
import type { AttendanceRecord } from "../model/types";

type AttendanceWorkspaceProps = {
  rows: AttendanceRecord[];
  busy: boolean;
  token: string;
  onToken: (token: string) => void;
  onRefresh: () => void;
  onExport: (month: string) => void;
};

export function AttendanceWorkspace({
  rows, busy, token, onToken, onRefresh, onExport,
}: AttendanceWorkspaceProps) {
  const [month, setMonth] = useState("");
  return (
    <section className="overflow-x-auto rounded-2xl border bg-white shadow-sm">
      <div className="space-y-4 border-b p-5">
        <h2 className="font-semibold">Attendance records</h2>
        <p className="text-sm text-slate-500">A new scan may take a moment to appear. Use Load records to refresh.</p>
        <div className="flex flex-wrap items-end gap-3">
          <label className="grid gap-1 text-sm">
            Demo access token
            <input
              type="password" value={token} onChange={(event) => onToken(event.target.value)}
              disabled={busy} autoComplete="off" className="rounded-lg border px-3 py-2"
            />
          </label>
          <button disabled={busy || !token.trim()} onClick={onRefresh} className="rounded-lg border px-3 py-2 text-sm disabled:opacity-50">
            Load records
          </button>
          <label className="grid gap-1 text-sm">
            Export month
            <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} className="rounded-lg border px-3 py-2" />
          </label>
          <button disabled={busy || !token.trim() || !month} onClick={() => onExport(month)} className="rounded-lg border px-3 py-2 text-sm disabled:opacity-50">
            Download HR CSV
          </button>
        </div>
      </div>
      <table className="w-full min-w-[760px] text-left text-sm">
        <thead className="bg-slate-50 text-xs uppercase text-slate-500">
          <tr>
            {["Event", "Employee", "Site / Station", "Direction", "Command", "Evidence", "Eligibility"].map((heading) => (
              <th key={heading} className="px-4 py-3">
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.eventId} className="border-t">
              <td className="max-w-32 truncate px-4 py-3 font-mono text-xs">{row.eventId}</td>
              <td className="px-4 py-3">{row.employeeId}</td>
              <td className="px-4 py-3">
                {row.siteId} · {row.stationId}
              </td>
              <td className="px-4 py-3">{row.direction}</td>
              <td className="px-4 py-3">{row.command}</td>
              <td className="px-4 py-3">{row.evidence ?? "—"}</td>
              <td className="px-4 py-3">{row.eligible ? `Eligible${row.hrReview ? " · HR review" : ""}` : "Pending"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!rows.length && <p className="p-10 text-center text-sm text-slate-400">No records returned</p>}
    </section>
  );
}
