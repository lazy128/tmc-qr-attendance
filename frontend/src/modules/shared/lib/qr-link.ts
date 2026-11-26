const gatePath = "/gate";
const fragmentPrefix = "#qr=";

export function employeeQrPayload(token: string, origin: string) {
  try {
    const link = new URL(gatePath, import.meta.env["VITE_QR_SCAN_ORIGIN"] || origin);
    const loopback = /^(localhost|.*\.localhost|127\..*|\[::1\])$/.test(link.hostname);
    if (!loopback && (link.protocol === "http:" || link.protocol === "https:")) {
      link.hash = `${fragmentPrefix}${token}`;
      return link.href;
    }
  } catch {
    // The kiosk can still read a QR without a reachable browser origin.
  }
  return `tmc1:${token}`;
}

export function tokenFromQrLink(code: string) {
  if (!/^https?:\/\//i.test(code)) return undefined;
  try {
    const link = new URL(code);
    if (link.pathname === gatePath && link.hash.startsWith(fragmentPrefix)) {
      return link.hash.slice(fragmentPrefix.length);
    }
  } catch {
    // A malformed URL is handled by token validation.
  }
  return undefined;
}
