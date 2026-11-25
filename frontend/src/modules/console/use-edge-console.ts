import { useCallback, useState } from "react";
import { accessApi } from "../access/api/access-api";
import type { VerifyResult } from "../access/model/types";
import { attendanceApi } from "../attendance/api/attendance-api";
import type { AttendanceRecord } from "../attendance/model/types";
import { errorMessage } from "../shared/lib/error-message";
import { qrApi } from "../userqr/api/qr-api";

export type ConsoleTab = "access" | "attendance";

export function useEdgeConsole() {
  const [tab, setTab] = useState<ConsoleTab>("access");
  const [site, setSite] = useState("SITE-1");
  const [station, setStation] = useState("GATE-1");
  const [credential, setCredential] = useState("demo-credential");
  const [device, setDevice] = useState("demo-device");
  const [token, setToken] = useState("");
  const [direction, setDirection] = useState<"ENTRY" | "EXIT">("ENTRY");
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [rows, setRows] = useState<AttendanceRecord[]>([]);
  const [attendanceToken, setAttendanceToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const run = useCallback(async (action: () => Promise<void>) => {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }, []);

  const refreshAttendance = useCallback(() => {
    return run(async () => {
      setRows([]);
      setRows(await attendanceApi.list(attendanceToken.trim()));
    });
  }, [attendanceToken, run]);

  const updateAttendanceToken = (value: string) => {
    setAttendanceToken(value);
    setRows([]);
  };

  const exportAttendance = (month: string) =>
    run(async () => {
      const csv = await attendanceApi.exportCsv(attendanceToken.trim(), month);
      const url = URL.createObjectURL(csv);
      const link = document.createElement("a");
      link.href = url;
      link.download = `attendance-${month}.csv`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    });

  const issueQr = () =>
    run(async () => {
      setToken((await qrApi.issue(credential, device)).token);
    });

  const revokeQr = () =>
    run(async () => {
      await qrApi.revoke(credential);
      setToken("");
    });

  const verify = () =>
    run(async () => {
      setResult(null);
      setResult(
        await accessApi.verify(
          {
            eventId: crypto.randomUUID(),
            siteId: site,
            direction,
            scannedAt: new Date().toISOString(),
            token,
            deviceId: device,
          },
          station,
        ),
      );
    });

  return {
    tab,
    setTab,
    site,
    setSite,
    station,
    setStation,
    credential,
    setCredential,
    device,
    setDevice,
    token,
    setToken,
    direction,
    setDirection,
    result,
    rows,
    busy,
    error,
    issueQr,
    revokeQr,
    verify,
    refreshAttendance,
    attendanceToken,
    updateAttendanceToken,
    exportAttendance,
  };
}
