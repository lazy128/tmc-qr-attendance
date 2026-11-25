package tmc.shared.audit;

import java.time.Instant;

public interface AuditWriter {
  void append(String actor, String action, String target, String result, String correlationId, Instant time);
}
