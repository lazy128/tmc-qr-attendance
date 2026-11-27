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
      String[] fields = request.stationProofId() == null
          ? new String[] {request.stationId().value(), request.siteId().value(), request.direction().name(),
              request.submittedAt().toString(), request.deviceId(), request.token()}
          : new String[] {"station-proof", request.stationId().value(), request.siteId().value(),
              request.direction().name(), request.stationProofId()};
      for (String value : fields) {
        String field = Objects.toString(value, "");
        digest.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
