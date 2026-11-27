const gatePath = "/gate";
const fragmentPrefix = "#qr=";
const checkInPath = "/employee";
const stationFragment = "#station=";
const personalFragment = "#personal=";

function phoneQrLink(path: string, fragment: string, origin: string) {
  try {
    const link = new URL(path, import.meta.env["VITE_QR_SCAN_ORIGIN"] || origin);
    const loopback = /^(localhost|.*\.localhost|127\..*|\[::1\])$/.test(link.hostname);
    if (!loopback && (link.protocol === "http:" || link.protocol === "https:")) {
      link.hash = fragment;
      return link.href;
    }
  } catch {
    // The kiosk can still read a QR without a reachable browser origin.
  }
  return undefined;
}

export function employeeQrPayload(token: string, origin: string) {
  return phoneQrLink(checkInPath, personalFragment + token, origin) ?? `tmc1:${token}`;
}

export function guardQrPayload(token: string, origin: string) {
  return phoneQrLink(gatePath, fragmentPrefix + token, origin) ?? `tmc1:${token}`;
}

export function stationQrPayload(code: string, origin: string) {
  return phoneQrLink(checkInPath, stationFragment + code, origin) ?? `tmc-station:${code}`;
}

function fromQrLink(code: string, path: string, fragment: string) {
  if (!/^https?:\/\//i.test(code)) return undefined;
  try {
    const link = new URL(code);
    if (link.pathname === path && link.hash.startsWith(fragment)) {
      return link.hash.slice(fragment.length);
    }
  } catch {
    // A malformed URL is handled by token validation.
  }
  return undefined;
}

export function tokenFromQrLink(code: string) {
  return fromQrLink(code, gatePath, fragmentPrefix);
}

export function personalTokenFromQr(code: string) {
  return code.startsWith("tmc1:") ? code.slice(5) : fromQrLink(code, checkInPath, personalFragment);
}

export function stationCodeFromQr(code: string) {
  const prefix = "tmc-station:";
  return code.startsWith(prefix)
    ? code.slice(prefix.length)
    : fromQrLink(code, checkInPath, stationFragment);
}

export type CheckInCode = { kind: "personal" | "station"; code: string };

export function checkInCodeFromQr(raw: string): CheckInCode | null {
  const personal = personalTokenFromQr(raw);
  if (personal) return { kind: "personal", code: personal };
  const station = stationCodeFromQr(raw);
  return station ? { kind: "station", code: station } : null;
}
