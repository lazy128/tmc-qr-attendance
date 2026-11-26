import { useCallback, useState } from "react";
import { accessApi } from "../access/api/access-api";
import { scanEventId } from "../access/lib/scan-event-id";
import type { VerifyResult } from "../access/model/types";
import { attendanceApi } from "../attendance/api/attendance-api";
import type { AttendanceRecord } from "../attendance/model/types";
import { errorMessage } from "../shared/lib/error-message";
import { qrApi } from "../userqr/api/qr-api";
import type { IssuedQr } from "../userqr/model/issued-qr";

export type ConsoleTab = "overview" | "access" | "qr" | "attendance";

export function useEdgeConsole() {
  const [tab, setTab] = useState<ConsoleTab>("overview");
  const [site, setSite] = useState("SITE-1");
  const [station, setStation] = useState("GATE-1");
  const [credential, setCredential] = useState("demo-credential");
  const [device, setDevice] = useState("demo-device");
  const [token, setToken] = useState("");
  const [issuedQr, setIssuedQr] = useState<IssuedQr | null>(null);
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

  const issueQr = (bearer: string, proof: string) =>
    run(async () => {
      const { token: signed, expiresAt } = await qrApi.issue(credential.trim(), bearer.trim(), proof.trim());
      setIssuedQr({
        token: signed,
        credential: credential.trim(),
        expiresAt: Date.parse(expiresAt),
        used: false,
      });
    });

  const revokeQr = (bearer: string) =>
    run(async () => {
      const id = credential.trim();
      await qrApi.revoke(id, bearer.trim());
      if (issuedQr?.credential === id) {
        setIssuedQr(null);
        if (token === issuedQr.token) setToken("");
      }
    });

  const verify = (rawToken = token) =>
    run(async () => {
      const scannedToken = rawToken.trim();
      setToken(scannedToken);
      setResult(null);
      const verification = await accessApi.verify(
        {
          eventId: scanEventId(),
          siteId: site.trim(),
          direction,
          scannedAt: new Date().toISOString(),
          token: scannedToken,
          deviceId: device.trim(),
        },
        station.trim(),
      );
      setResult(verification);
      if (verification.decision === "ALLOW" || verification.reasonCode === "REPLAY") {
        setIssuedQr((previous) =>
          previous?.token === scannedToken ? { ...previous, used: true } : previous,
        );
      }
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
    issuedQr,
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
