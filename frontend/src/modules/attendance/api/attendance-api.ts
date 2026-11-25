import { download, request } from "../../shared/http/client";
import type { AttendanceRecord } from "../model/types";

export const attendanceApi = {
  list: (token: string) =>
    request<AttendanceRecord[]>("/attendance/v1/records", {
      headers: { Authorization: `Bearer ${token}` },
    }),
  exportCsv: (token: string, month: string) =>
    download(`/reports/v1/exports?${new URLSearchParams({ month })}`, {
      headers: { Authorization: `Bearer ${token}` },
    }),
};
