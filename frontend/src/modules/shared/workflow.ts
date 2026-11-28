import { request, download } from "./http/client";

export type Group = "STAFF" | "MANAGER" | "EXECUTIVE";
export type Employee = { id: string; name: string; accessGroup: Group; siteId: string };
export type AttendanceStatus = {
  employeeId: string;
  status: "NOT_ENTERED" | "IN_SHIFT" | "BETWEEN_PAIRS" | "LEFT";
  lastEntry: string | null;
  lastExit: string | null;
  workDate: string;
};
export type Pass = AttendanceStatus & { token: string; expiresAt: string };
export type Direction = "ENTRY" | "EXIT";
export type PayrollBatch = { number: number; rows: number; createdAt: string };
export type PayrollHistory = { pendingRows: number; batches: PayrollBatch[] };
export type GateIncident = {
  eventId: string;
  employeeName: string;
  employeeId: string;
  stationId: string;
  siteId: string;
  direction: Direction;
  scannedAt: string;
};
export type StationCheckIn = {
  eventId?: string;
  direction: Direction;
  decision: "ALLOW" | "DENY";
  reasonCode: string | null;
  command: string;
  evidence?: string | null;
  scannedAt: string;
};
export type BrowserDevice = {
  reference: string;
  code: string;
  name: string | null;
  label: string | null;
  lastLogin: string | null;
  trusted: boolean;
  current: boolean;
};
export type DeviceList = { canManage: boolean; devices: BrowserDevice[] };
export type StationCode = { code: string; expiresAt: string };
export type EmployeeActivity = {
  employeeId: string;
  otherSessions: number;
  currentDeviceName: string | null;
  recentLogins: {
    signedInAt: string;
    deviceLabel: string;
    deviceName: string | null;
    deviceCode: string | null;
    clientIp: string | null;
    current: boolean;
    active: boolean;
  }[];
  scans: {
    eventId: string;
    scannedAt: string;
    siteId: string;
    stationName: string;
    direction: "ENTRY" | "EXIT";
    suspicious: boolean;
  }[];
};
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
  siteId: string | null;
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
  closeOtherSessions: () =>
    request<{ closed: number }>("/auth/v1/sessions/close-others", { method: "POST" }),
  nameDevice: (name: string) =>
    request<void>("/auth/v1/device-name", { method: "PATCH", body: JSON.stringify({ name }) }),
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

function devicePath(employeeId?: string) {
  return employeeId
    ? `/workplace/v1/hr/employees/${encodeURIComponent(employeeId)}/devices`
    : "/workplace/v1/me/devices";
}

export const workflow = {
  config: (signal: AbortSignal) =>
    request<{ personalSelfEnabled: boolean }>("/workplace/v1/config", { signal }),
  devices: (signal: AbortSignal, employeeId?: string) =>
    request<DeviceList>(devicePath(employeeId), { signal }),
  changeDevice: (reference: string, trusted: boolean, employeeId?: string) =>
    request<void>(`${devicePath(employeeId)}/${encodeURIComponent(reference)}`, {
      method: "PATCH",
      body: JSON.stringify({ trusted }),
    }),
  employee: (signal: AbortSignal) => request<Employee>("/workplace/v1/me/employee", { signal }),
  pass: (signal: AbortSignal, direction?: Direction) =>
    request<Pass>("/workplace/v1/me/qr", {
      method: "POST",
      signal,
      ...(direction ? { body: JSON.stringify({ direction }) } : {}),
    }),
  attendance: (signal: AbortSignal) =>
    request<AttendanceStatus>("/workplace/v1/me/attendance", { signal }),
  activity: (signal: AbortSignal) =>
    request<EmployeeActivity>("/workplace/v1/me/activity", { signal }),
  station: (signal: AbortSignal) => request<Station>("/workplace/v1/station", { signal }),
  stationCode: (stationId: string, direction: Direction, signal: AbortSignal) =>
    request<StationCode>(
      `/workplace/v1/stations/${encodeURIComponent(stationId)}/qr/${direction}`,
      {
        method: "POST",
        signal,
      },
    ),
  checkIn: (code: string, signal: AbortSignal) =>
    request<StationCheckIn>("/workplace/v1/me/check-in", {
      method: "POST",
      body: JSON.stringify({ code }),
      signal,
    }),
  checkInStatus: (eventId: string, signal: AbortSignal) =>
    request<StationCheckIn>(`/workplace/v1/me/check-in/status/${encodeURIComponent(eventId)}`, {
      signal,
    }),
  gateIncidents: (signal: AbortSignal) =>
    request<{ canReview: boolean; incidents: GateIncident[] }>("/gates/v1/incidents", { signal }),
  reviewGateIncident: (eventId: string, passed: boolean, note: string) =>
    request<void>(`/gates/v1/incidents/${encodeURIComponent(eventId)}/review`, {
      method: "POST",
      body: JSON.stringify({ passed, note }),
    }),
  personalCheckIn: (code: string, signal: AbortSignal) =>
    request<StationCheckIn>("/workplace/v1/me/check-in/personal", {
      method: "POST",
      body: JSON.stringify({ code }),
      signal,
    }),
  stations: (signal: AbortSignal) =>
    request<{ stations: Station[]; canManage: boolean; siteId: string; siteIds: string[] }>(
      "/admin/v1/stations",
      {
        signal,
      },
    ),
  rows: (month: string, employeeId: string, signal: AbortSignal, siteId = "") =>
    request<Timesheet[]>(`${sheets}?${timesheetQuery(month, siteId, employeeId)}`, { signal }),
  roster: (signal: AbortSignal, siteId = "") =>
    request<{ employeeId: string; employeeName: string }[]>(
      `${sheets}/employees${siteId ? `?siteId=${encodeURIComponent(siteId)}` : ""}`,
      { signal },
    ),
  details: (employeeId: string, date: string) =>
    request<ScanDetail[]>(`${sheets}/details?${new URLSearchParams({ employeeId, date })}`),
  correct: (employeeId: string, date: string, exitAt: string, reason: string) =>
    request<Timesheet>(`${sheets}/corrections`, {
      method: "POST",
      body: JSON.stringify({ employeeId, date, exitAt, reason }),
    }),
  payrollHistory: (month: string, signal: AbortSignal) =>
    request<PayrollHistory>(`${sheets}/payroll/${encodeURIComponent(month)}`, { signal }),
  capturePayroll: (month: string) =>
    request<PayrollBatch | undefined>(`${sheets}/payroll/${encodeURIComponent(month)}`, {
      method: "POST",
    }),
  downloadPayroll: (month: string, number: number) =>
    download(`${sheets}/payroll/${encodeURIComponent(month)}/${number}`, {}),
  export: (month: string, type: "xlsx" | "csv", siteId = "", employeeId = "") =>
    download(`${sheets}/${type}?${timesheetQuery(month, siteId, employeeId)}`, {}),
};

function timesheetQuery(month: string, siteId: string, employeeId: string) {
  return new URLSearchParams({
    month,
    ...(siteId ? { siteId } : {}),
    ...(employeeId ? { employeeId } : {}),
  });
}

export const workplaceSiteNames: Record<string, string> = {
  "SITE-BD": "Bình Dương",
  "SITE-LA": "Long An",
};
export function siteName(siteId: string) {
  return workplaceSiteNames[siteId === "SITE-1" ? "SITE-BD" : siteId] ?? siteId;
}

export function canManageTimesheets(role: Role) {
  return role === "ADMIN" || role === "HR";
}

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
