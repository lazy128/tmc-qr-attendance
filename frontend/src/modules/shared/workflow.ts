import { request, download } from "./http/client";

export type Group = "STAFF" | "MANAGER" | "EXECUTIVE";
export type Employee = { id: string; name: string; accessGroup: Group; siteId: string };
export type AttendanceStatus = {
  employeeId: string;
  status: "NOT_ENTERED" | "IN_SHIFT" | "LEFT";
  lastEntry: string | null;
  lastExit: string | null;
};
export type Pass = AttendanceStatus & { token: string; expiresAt: string };
export type Station = {
  siteId: string;
  stationId: string;
  gateId: string;
  status: "ACTIVE" | "DISABLED";
  lastHeartbeatAt: string | null;
  gate: {
    name: string;
    type: "ATTENDANCE_GATE" | "ZONE_GATE";
    allowedGroups: Group[];
    directions: ("ENTRY" | "EXIT")[];
    opensAt?: string | null;
    closesAt?: string | null;
  };
};
export type Role = "EMPLOYEE" | "MANAGER" | "HR" | "GUARD" | "ADMIN";
export type Account = {
  username: string;
  displayName: string;
  role: Role;
  enabled: boolean;
  employeeId: string | null;
  stationId: string | null;
  changeRequired: boolean;
};
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
const sheets = "/attendance/v1/timesheets";

export const accountApi = {
  me: (signal: AbortSignal) => request<Account>("/auth/v1/me", { signal }),
  login: (username: string, password: string) =>
    request<Account>("/auth/v1/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<void>("/auth/v1/logout", { method: "POST" }),
  password: (current: string, replacement: string) =>
    request<void>("/auth/v1/password", {
      method: "POST",
      body: JSON.stringify({ current, replacement }),
    }),
  list: (signal: AbortSignal) => request<Account[]>("/workplace/v1/accounts", { signal }),
  create: (body: Record<string, FormDataEntryValue | null>) =>
    request<Account>("/workplace/v1/accounts", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  update: (username: string, body: { enabled?: boolean; password?: string }) =>
    request<Account>(`/workplace/v1/accounts/${encodeURIComponent(username)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
};

export const workflow = {
  employee: (signal: AbortSignal) => request<Employee>("/workplace/v1/me/employee", { signal }),
  pass: (signal: AbortSignal) => request<Pass>("/workplace/v1/me/qr", { method: "POST", signal }),
  attendance: (signal: AbortSignal) =>
    request<AttendanceStatus>("/workplace/v1/me/attendance", { signal }),
  station: (signal: AbortSignal) => request<Station>("/workplace/v1/station", { signal }),
  stations: (signal: AbortSignal) =>
    request<{ stations: Station[]; canManage: boolean; siteId: string }>("/admin/v1/stations", {
      signal,
    }),
  rows: (month: string, employeeId: string, signal: AbortSignal) =>
    request<Timesheet[]>(
      `${sheets}?${new URLSearchParams({ month, ...(employeeId ? { employeeId } : {}) })}`,
      { signal },
    ),
  roster: (signal: AbortSignal) =>
    request<{ employeeId: string; employeeName: string }[]>(`${sheets}/employees`, {
      signal,
    }),
  status: (month: string, signal: AbortSignal) =>
    request<{ status: string; updatedAt: string | null }>(
      `${sheets}/export-status?month=${encodeURIComponent(month)}`,
      { signal },
    ),
  details: (employeeId: string, date: string) =>
    request<ScanDetail[]>(`${sheets}/details?${new URLSearchParams({ employeeId, date })}`),
  correct: (employeeId: string, date: string, exitAt: string, reason: string) =>
    request<Timesheet>(`${sheets}/corrections`, {
      method: "POST",
      body: JSON.stringify({ employeeId, date, exitAt, reason }),
    }),
  export: (month: string, type: "xlsx" | "csv") =>
    download(`${sheets}/${type}?month=${encodeURIComponent(month)}`, {}),
};

export const roleLabel: Record<Role, string> = {
  EMPLOYEE: "Nhân viên",
  MANAGER: "Quản lý",
  HR: "Nhân sự",
  GUARD: "Bảo vệ",
  ADMIN: "Quản trị viên",
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
