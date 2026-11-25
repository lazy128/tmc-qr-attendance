package tmc.access.domain;

import java.time.Instant;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;

public record AccessAttempt(
    EventId eventId,
    String correlationId,
    String employeeId,
    SiteId siteId,
    StationId stationId,
    Direction direction,
    Instant scannedAt,
    Instant receivedAt,
    String credentialId,
    int credentialVersion,
    String jti,
    Decision decision,
    Reason reasonCode,
    Command command,
    Passage passage,
    boolean offlineAccepted,
    Evidence evidence,
    boolean suspicious
) {
  public AccessAttempt withCommand(Command next) {
    boolean valid =
        (command == Command.NOT_SENT && next == Command.SENT)
            || (command == Command.SENT && (next == Command.ACKED || next == Command.FAILED || next == Command.UNKNOWN))
            || (command == Command.UNKNOWN && (next == Command.ACKED || next == Command.FAILED));
    if (!valid) {
      throw new IllegalStateException("invalid command transition: " + command + " -> " + next);
    }
    return new AccessAttempt(
        eventId,
        correlationId,
        employeeId,
        siteId,
        stationId,
        direction,
        scannedAt,
        receivedAt,
        credentialId,
        credentialVersion,
        jti,
        decision,
        reasonCode,
        next,
        passage,
        offlineAccepted,
        next == Command.ACKED && passage != Passage.CONFIRMED ? Evidence.ACK_ONLY : evidence,
        suspicious
    );
  }

  public AccessAttempt withPassage() {
    return new AccessAttempt(
        eventId,
        correlationId,
        employeeId,
        siteId,
        stationId,
        direction,
        scannedAt,
        receivedAt,
        credentialId,
        credentialVersion,
        jti,
        decision,
        reasonCode,
        command,
        Passage.CONFIRMED,
        offlineAccepted,
        Evidence.PASSAGE_CONFIRMED,
        suspicious
    );
  }

  public AccessAttempt withUnknownOutcome() {
    return new AccessAttempt(
        eventId, correlationId, employeeId, siteId, stationId, direction, scannedAt, receivedAt,
        credentialId, credentialVersion, jti, decision, reasonCode, Command.UNKNOWN, passage,
        offlineAccepted, passage == Passage.CONFIRMED ? evidence : null, suspicious
    );
  }
}
