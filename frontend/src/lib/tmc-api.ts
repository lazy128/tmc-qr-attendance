export { API_BASE } from "../modules/shared/config/api-base";
export { accessApi } from "../modules/access/api/access-api";
export { attendanceApi } from "../modules/attendance/api/attendance-api";
export { qrApi } from "../modules/userqr/api/qr-api";
export type { VerifyPayload, VerifyResult } from "../modules/access/model/types";
export type { AttendanceRecord } from "../modules/attendance/model/types";

import { accessApi } from "../modules/access/api/access-api";
import { attendanceApi } from "../modules/attendance/api/attendance-api";
import { qrApi } from "../modules/userqr/api/qr-api";

export const tmcApi = {
  issueQr: qrApi.issue,
  revokeQr: qrApi.revoke,
  verify: accessApi.verify,
  attendance: attendanceApi.list,
};
