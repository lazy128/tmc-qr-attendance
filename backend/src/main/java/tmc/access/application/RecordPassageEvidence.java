package tmc.access.application;

import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.PresenceTracker;

public final class RecordPassageEvidence {
  private final AttemptStore attempts;
  private final PresenceTracker presence;

  public RecordPassageEvidence(AttemptStore attempts, PresenceTracker presence) {
    this.attempts = attempts;
    this.presence = presence;
  }

  public void confirmed(String eventId, String stationId) {
    var attempt = attempts.findAttempt(eventId).orElseThrow(() -> new IllegalArgumentException("attempt not found"));
    if (!attempt.stationId().value().equals(stationId)) {
      throw new IllegalArgumentException("Attempt does not belong to this station");
    }
    if (attempt.command() == tmc.access.domain.AccessStates.Command.NOT_SENT
        || attempt.command() == tmc.access.domain.AccessStates.Command.FAILED
        || attempt.command() == tmc.access.domain.AccessStates.Command.EXPIRED) {
      throw new IllegalStateException("Passage evidence cannot be linked to this attempt");
    }
    var confirmed = attempt.withPassage();
    attempts.update(confirmed);
    if (attempt.attendanceGate()) {
      presence.confirmed(attempt.siteId().value(), attempt.employeeId(), attempt.direction(), eventId);
    }
  }
}
