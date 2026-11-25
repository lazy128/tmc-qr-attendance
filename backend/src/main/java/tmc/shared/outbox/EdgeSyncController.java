package tmc.shared.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

@RestController
public class EdgeSyncController {
  private final CentralSyncReceiver receiver;

  public EdgeSyncController(CentralSyncReceiver receiver) {
    this.receiver = receiver;
  }

  @PostMapping("/edge/v1/sync")
  public List<SyncResult> sync(@RequestBody List<SyncEvent> batch) {
    return batch.stream().map(event -> {
      var attempt = new AccessAttempt(
          new EventId(event.eventId()),
          event.correlationId(),
          event.employeeId(),
          new SiteId(event.siteId()),
          new StationId(event.stationId()),
          Direction.valueOf(event.direction()),
          event.scannedAt(),
          event.receivedAt(),
          event.credentialId(),
          event.credentialVersion(),
          event.jti(),
          Decision.valueOf(event.decision()),
          event.reasonCode() == null ? null : Reason.valueOf(event.reasonCode()),
          Command.valueOf(event.command()),
          Passage.valueOf(event.passage()),
          event.offlineAccepted(),
          event.evidence() == null ? null : Evidence.valueOf(event.evidence()),
          false
      );
      var result = receiver.receive(attempt);
      String status = result.suspicious() ? "SUSPICIOUS" : result.duplicate() ? "DUPLICATE" : "ACCEPTED";
      return new SyncResult(event.eventId(), status);
    }).toList();
  }

  public record SyncEvent(
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
      boolean offlineAccepted
  ) {}

  public record SyncResult(String eventId, String status) {}
}
