package tmc.access.domain.port;

import java.util.Optional;
import tmc.access.domain.AccessAttempt;

public interface AttemptStore {
  void persistPrepared(AccessAttempt attempt, String jti);

  void persistDenied(AccessAttempt attempt);

  default void auditRejection(AccessAttempt attempt) {}

  void update(AccessAttempt attempt);

  default void recordOutcome(AccessAttempt attempt) {
    update(attempt);
  }

  Optional<AccessAttempt> findAttempt(String eventId);
}
