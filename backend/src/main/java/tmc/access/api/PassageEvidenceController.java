package tmc.access.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.RecordPassageEvidence;
import tmc.shared.DemoIdentity;

@RestController
public class PassageEvidenceController {
  private final RecordPassageEvidence recorder;
  private final DemoIdentity identity;
  private final String station;

  public PassageEvidenceController(
      RecordPassageEvidence recorder, DemoIdentity identity, @Value("${tmc.edge.station:GATE-1}") String station
  ) {
    this.recorder = recorder;
    this.identity = identity;
    this.station = station;
  }

  @PostMapping("/gates/v1/passage-events")
  public Map<String, String> confirm(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("X-Station-Id") String stationId, @RequestBody PassageRequest request
  ) {
    String actor = identity.authenticate(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enter a valid demo bearer token"));
    if (!"guard".equals(actor) || !station.equals(stationId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo passage evidence requires the assigned guard");
    }
    recorder.confirmed(request.eventId(), stationId);
    return Map.of("status", "CONFIRMED");
  }

  public record PassageRequest(String eventId) {}
}
