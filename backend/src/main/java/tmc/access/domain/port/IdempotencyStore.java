package tmc.access.domain.port;

import java.util.Optional;
import tmc.access.domain.AccessAttempt;

public interface IdempotencyStore {
  Optional<IdempotencyEntry> findEvent(String eventId);

  boolean reserve(String eventId, String requestFingerprint);

  void complete(String eventId, AccessAttempt result);

  void cancel(String eventId);
}
