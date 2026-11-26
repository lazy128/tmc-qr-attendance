export function stripQrPrefix(raw: string) {
  const code = raw.trim();
  return code.startsWith("tmc1:") ? code.slice(5) : code;
}

export function parseScanToken(raw: string) {
  const token = stripQrPrefix(raw);
  if (token.length > 4096 || !/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token)) {
    throw new Error("Mã QR không chứa token truy cập TMC hợp lệ.");
  }
  try {
    const payload = token.split(".")[1];
    if (!payload) throw new Error("Missing token payload");
    const claims = JSON.parse(atob(payload.replaceAll("-", "+").replaceAll("_", "/"))) as {
      did?: unknown;
    };
    if (typeof claims.did === "string" && claims.did.length <= 256 && claims.did) {
      // The Edge validates the signature and compares this field to its credential record.
      return { token, deviceId: claims.did };
    }
  } catch {
    // A malformed payload has no device binding.
  }
  throw new Error("Token thiếu định danh thiết bị.");
}
