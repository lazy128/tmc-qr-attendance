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
import tmc.access.application.PermissionPolicy;
import tmc.shared.Identity;

@RestController
public class PassageEvidenceController {
  private final RecordPassageEvidence recorder;
  private final Identity identity;
  private final PermissionPolicy permissions;
  private final String station;

  public PassageEvidenceController(
      RecordPassageEvidence recorder, Identity identity, PermissionPolicy permissions,
      @Value("${tmc.edge.station:GATE-1}") String station
  ) {
    this.recorder = recorder;
    this.identity = identity;
    this.permissions = permissions;
    this.station = station;
  }

  @PostMapping("/gates/v1/passage-events")
  public Map<String, String> confirm(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("X-Station-Id") String stationId, @RequestBody PassageRequest request
  ) {
    String actor = identity.authenticate(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Đăng nhập tài khoản bảo vệ."));
    if (!permissions.permits(actor, "presence:correct") || !identity.assignedStation(actor).orElse(station).equals(stationId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bảo vệ chỉ được xác nhận tại trạm được giao.");
    }
    recorder.confirmed(request.eventId(), stationId);
    return Map.of("status", "CONFIRMED");
  }

  public record PassageRequest(String eventId) {}
}
