export function scanEventId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  // getRandomValues is also available on the HTTP LAN demo.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `scan-${hex}`;
}
