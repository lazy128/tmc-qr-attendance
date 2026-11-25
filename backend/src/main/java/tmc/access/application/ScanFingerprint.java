package tmc.access.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import tmc.access.domain.AccessRequest;

final class ScanFingerprint {
  private ScanFingerprint() {}

  static String of(AccessRequest request) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value : new String[] {
          request.stationId().value(), request.siteId().value(), request.direction().name(),
          request.scannedAt().toString(), request.deviceId(), request.token()
      }) {
        String field = Objects.toString(value, "");
        digest.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
