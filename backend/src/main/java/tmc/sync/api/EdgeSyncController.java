package tmc.sync.api;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
import tmc.shared.outbox.EdgeSyncEvent;
import tmc.sync.application.CentralSyncReceiver;
import tmc.sync.application.CentralAttendanceConsumer;

@RestController
public class EdgeSyncController {
  private final CentralSyncReceiver receiver;
  private final MeterRegistry metrics;
  private final Optional<CentralAttendanceConsumer> attendance;
  private final String syncToken;

  public EdgeSyncController(CentralSyncReceiver receiver, MeterRegistry metrics,
      Optional<CentralAttendanceConsumer> attendance, @Value("${TMC_EDGE_SYNC_TOKEN:}") String syncToken) {
    this.receiver = receiver;
    this.metrics = metrics;
    this.attendance = attendance;
    this.syncToken = syncToken;
    if (attendance.isPresent() && syncToken.isBlank()) {
      throw new IllegalArgumentException("Set TMC_EDGE_SYNC_TOKEN before enabling Central attendance");
    }
  }

  @PostMapping("/edge/v1/sync")
  public List<SyncResult> sync(@RequestBody List<EdgeSyncEvent> batch,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    if (attendance.isPresent() && (authorization == null || !MessageDigest.isEqual(
        ("Bearer " + syncToken).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
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
          false, !Boolean.TRUE.equals(event.zoneGate())
      );
      var result = receiver.receive(attempt);
      attendance.ifPresent(consumer -> consumer.record(result.attempt()));
      String status = result.suspicious() ? "SUSPICIOUS" : result.duplicate() ? "DUPLICATE" : "ACCEPTED";
      if (result.suspicious() && !result.duplicate()) {
        metrics.counter("tmc.central.sync.suspicious").increment();
      }
      return new SyncResult(event.eventId(), status);
    }).toList();
  }

  public record SyncResult(String eventId, String status) {}
}
