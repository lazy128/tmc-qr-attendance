import { request, download } from "./http/client";

export type Group = "STAFF" | "MANAGER" | "EXECUTIVE";
export type Employee = { id: string; name: string; accessGroup: Group; siteId: string };
export type Pass = {
  token: string;
  expiresAt: string;
  status: "NOT_ENTERED" | "IN_SHIFT" | "LEFT";
  lastEntry: string | null;
  lastExit: string | null;
};
export type Station = {
  siteId: string;
  stationId: string;
  gate: {
    name: string;
    type: "ATTENDANCE_GATE" | "ZONE_GATE";
    allowedGroups: Group[];
    directions: ("ENTRY" | "EXIT")[];
  };
};
export type Role = "EMPLOYEE" | "MANAGER" | "HR";
export type Timesheet = {
  employeeId: string;
  employeeName: string;
  siteId: string;
  workDate: string;
  firstIn: string | null;
  lastOut: string | null;
  workedMinutes: number;
  status: "COMPLETE" | "MISSING_EXIT" | "DUPLICATE" | "REVIEW";
  reviewFlag: boolean;
};
export type ScanDetail = {
  eventId: string;
  command: string;
  evidence: string | null;
  direction: string;
  scannedAt: string;
};
const auth = (bearer: string) => ({ Authorization: `Bearer ${bearer}` });
const sheets = "/attendance/v1/timesheets";

export const workflow = {
  employees: (signal: AbortSignal) => request<Employee[]>("/demo/v1/employees", { signal }),
  pass: (id: string, signal: AbortSignal) =>
    request<Pass>(`/demo/v1/employees/${encodeURIComponent(id)}/qr`, { method: "POST", signal }),
  gates: (base: string, signal: AbortSignal) =>
    request<Station[]>("/demo/v1/gates", { signal }, base),
  session: (role: Role, employeeId: string, signal: AbortSignal) =>
    request<{ bearer: string }>("/demo/v1/session", {
      method: "POST",
      body: JSON.stringify({ role, employeeId }),
      signal,
    }),
  rows: (bearer: string, month: string, employeeId: string, signal: AbortSignal) =>
    request<Timesheet[]>(
      `${sheets}?${new URLSearchParams({ month, ...(employeeId ? { employeeId } : {}) })}`,
      { headers: auth(bearer), signal },
    ),
  roster: (bearer: string, signal: AbortSignal) =>
    request<{ employeeId: string; employeeName: string }[]>(`${sheets}/employees`, {
      headers: auth(bearer),
      signal,
    }),
  status: (bearer: string, month: string, signal: AbortSignal) =>
    request<{ status: string; updatedAt: string | null }>(
      `${sheets}/export-status?month=${encodeURIComponent(month)}`,
      { headers: auth(bearer), signal },
    ),
  details: (bearer: string, employeeId: string, date: string) =>
    request<ScanDetail[]>(`${sheets}/details?${new URLSearchParams({ employeeId, date })}`, {
      headers: auth(bearer),
    }),
  correct: (bearer: string, employeeId: string, date: string, exitAt: string, reason: string) =>
    request<Timesheet>(`${sheets}/corrections`, {
      method: "POST",
      headers: auth(bearer),
      body: JSON.stringify({ employeeId, date, exitAt, reason }),
    }),
  export: (bearer: string, month: string, type: "xlsx" | "csv") =>
    download(`${sheets}/${type}?month=${encodeURIComponent(month)}`, { headers: auth(bearer) }),
};

export const groupLabel: Record<Group, string> = {
  STAFF: "Nhân viên",
  MANAGER: "Quản lý",
  EXECUTIVE: "Lãnh đạo",
};
export const businessZone = "Asia/Ho_Chi_Minh";
export function displayTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("vi-VN", {
        hour: "2-digit",
        minute: "2-digit",
        timeZone: businessZone,
      }).format(new Date(value))
    : "—";
}
