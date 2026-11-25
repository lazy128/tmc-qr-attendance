package tmc.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public final class AuditHash {
  private AuditHash() {}

  public static String calculate(
      String previous, String actor, String action, String target, String result, String correlation, Instant time
  ) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String field : new String[]{previous, actor, action, target, result, correlation, time.toString()}) {
        if (field == null) hash.update("-1:".getBytes(StandardCharsets.UTF_8));
        else hash.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
