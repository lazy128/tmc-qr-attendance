package tmc.sync.application;

import java.util.HashMap;
import java.util.Map;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Passage;

public final class CentralSyncReceiver {
  private final Map<String, AccessAttempt> events = new HashMap<>();
  private final Map<String, String> jtiSites = new HashMap<>();

  public synchronized Result receive(AccessAttempt attempt) {
    var previous = events.get(attempt.eventId().value());
    if (previous != null) {
      if (!previous.siteId().equals(attempt.siteId())
          || !java.util.Objects.equals(previous.jti(), attempt.jti())) {
        return new Result(previous, true, true);
      }
      var updated = previous;
      if ((previous.command() == Command.UNKNOWN || previous.command() == Command.SENT)
          && (attempt.command() == Command.ACKED || attempt.command() == Command.FAILED)) {
        updated = updated.withCommand(attempt.command());
      }
      if (updated.passage() != Passage.CONFIRMED && attempt.passage() == Passage.CONFIRMED) {
        updated = updated.withPassage();
      }
      events.put(attempt.eventId().value(), updated);
      return new Result(updated, updated.suspicious(), updated.equals(previous));
    }
    String jti = attempt.jti();
    boolean suspicious = jti != null && jtiSites.containsKey(jti) && !jtiSites.get(jti).equals(attempt.siteId().value());
    if (jti != null) {
      jtiSites.putIfAbsent(jti, attempt.siteId().value());
    }
    AccessAttempt stored = suspicious
        ? new AccessAttempt(
            attempt.eventId(),
            attempt.correlationId(),
            attempt.employeeId(),
            attempt.siteId(),
            attempt.stationId(),
            attempt.direction(),
            attempt.scannedAt(),
            attempt.receivedAt(),
            attempt.credentialId(),
            attempt.credentialVersion(),
            attempt.jti(),
            attempt.decision(),
            attempt.reasonCode(),
            attempt.command(),
            attempt.passage(),
            attempt.offlineAccepted(),
            attempt.evidence(),
            true, attempt.attendanceGate()
        )
        : attempt;
    events.put(attempt.eventId().value(), stored);
    return new Result(stored, suspicious, false);
  }

  public record Result(AccessAttempt attempt, boolean suspicious, boolean duplicate) {}
}
