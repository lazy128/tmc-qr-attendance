package tmc.shared.outbox;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire contract between the Edge outbox and the Central sync endpoint. */
public record EdgeSyncEvent(
    String eventId,
    String correlationId,
    String employeeId,
    String siteId,
    String stationId,
    String direction,
    Instant scannedAt,
    Instant receivedAt,
    String credentialId,
    int credentialVersion,
    String jti,
    String decision,
    String reasonCode,
    String command,
    String passage,
    String evidence,
    boolean offlineAccepted,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean zoneGate
) {
  public EdgeSyncEvent(String eventId, String correlationId, String employeeId, String siteId, String stationId,
      String direction, Instant scannedAt, Instant receivedAt, String credentialId, int credentialVersion,
      String jti, String decision, String reasonCode, String command, String passage, String evidence,
      boolean offlineAccepted) {
    this(eventId, correlationId, employeeId, siteId, stationId, direction, scannedAt, receivedAt,
        credentialId, credentialVersion, jti, decision, reasonCode, command, passage, evidence, offlineAccepted, null);
  }
}
