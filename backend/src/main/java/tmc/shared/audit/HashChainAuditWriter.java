package tmc.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class HashChainAuditWriter implements AuditWriter {
  private String head = "0";
  private final List<String> chain = new ArrayList<>();

  @Override
  public synchronized void append(
      String actor,
      String action,
      String target,
      String result,
      String correlation,
      Instant time
  ) {
    try {
      String value = head + "|" + actor + "|" + action + "|" + target + "|" + result + "|" + correlation + "|" + time;
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      head = HexFormat.of().formatHex(digest);
      chain.add(head);
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  public synchronized List<String> entries() {
    return List.copyOf(chain);
  }
}
