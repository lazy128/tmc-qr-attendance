package tmc.sync.application;

import java.util.Objects;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;

public final class CentralSyncReceiver {
  private final CentralEventStore events;

  public CentralSyncReceiver(CentralEventStore events) {
    this.events = events;
  }

  public Result receive(AccessAttempt attempt) {
    return events.locked(() -> receiveLocked(attempt));
  }

  private Result receiveLocked(AccessAttempt attempt) {
    var previous = events.find(attempt.eventId().value());
    if (previous != null) {
      if (!previous.siteId().equals(attempt.siteId()) || previous.channel() != attempt.channel()
          || !Objects.equals(previous.jti(), attempt.jti())) return new Result(previous, true, true);
      var updated = updateOutcome(previous, attempt);
      boolean duplicate = updated.equals(previous);
      if (!duplicate) events.save(updated, false, false);
      return new Result(updated, updated.suspicious(), duplicate);
    }

    String jti = attempt.jti();
    String firstSite = jti == null ? null : events.firstSite(jti);
    boolean suspicious = firstSite != null && !firstSite.equals(attempt.siteId().value());
    var stored = suspicious ? new AccessAttempt(attempt.eventId(), attempt.correlationId(), attempt.employeeId(),
        attempt.siteId(), attempt.stationId(), attempt.direction(), attempt.scannedAt(), attempt.receivedAt(),
        attempt.credentialId(), attempt.credentialVersion(), attempt.jti(), attempt.decision(), attempt.reasonCode(),
        attempt.command(), attempt.passage(), attempt.offlineAccepted(), attempt.evidence(), true,
        attempt.attendanceGate(), attempt.channel()) : attempt;
    events.save(stored, true, jti != null && firstSite == null);
    return new Result(stored, suspicious, false);
  }

  private static AccessAttempt updateOutcome(AccessAttempt previous, AccessAttempt incoming) {
    var updated = previous;
    if ((previous.command() == Command.UNKNOWN || previous.command() == Command.SENT)
        && (incoming.command() == Command.ACKED || incoming.command() == Command.FAILED
            || incoming.command() == Command.EXPIRED)) {
      updated = previous.withCommand(incoming.command());
    }
    if (updated.command() == Command.EXPIRED && updated.evidence() != Evidence.MANUAL_PASSAGE
        && updated.evidence() != Evidence.MANUAL_NO_PASSAGE) {
      if (incoming.evidence() == Evidence.MANUAL_PASSAGE) return updated.withGateReview(true);
      if (incoming.evidence() == Evidence.MANUAL_NO_PASSAGE) return updated.withGateReview(false);
    }
    if (updated.command() != Command.EXPIRED && updated.passage() != Passage.CONFIRMED
        && incoming.passage() == Passage.CONFIRMED) return updated.withPassage();
    return updated;
  }

  public record Result(AccessAttempt attempt, boolean suspicious, boolean duplicate) {}
}
