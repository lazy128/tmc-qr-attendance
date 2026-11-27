package tmc.access.api;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.access.domain.port.PresenceCorrectionStore;
import tmc.access.domain.StationId;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.shared.Identity;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class PresenceCorrectionController {
  private final PresenceCorrectionStore corrections;
  private final Identity identity;
  private final PermissionPolicy policy;
  private final ClockProvider clock;
  private final JdbcStationRegistry stations;

  public PresenceCorrectionController(
      PresenceCorrectionStore corrections, Identity identity, PermissionPolicy policy, ClockProvider clock,
      JdbcStationRegistry stations
  ) {
    this.corrections = corrections;
    this.identity = identity;
    this.policy = policy;
    this.clock = clock;
    this.stations = stations;
  }

  @PostMapping("/gates/v1/presence-corrections")
  public Map<String, String> correct(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader("X-Station-Id") String stationId,
      @RequestBody Correction request
  ) {
    String actor = identity.authenticate(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Đăng nhập tài khoản bảo vệ."));
    if (!policy.permits(actor, "presence:correct")
        || !identity.assignedStation(actor).orElse(stations.assignedStation()).equals(stationId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Correction requires the assigned guard");
    }
    var site = stations.siteFor(new StationId(stationId));
    if (site == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trạm không hoạt động.");
    if (!"MISSED_EXIT".equals(request.reason()) || request.lastEntryEventId() == null) {
      throw new IllegalArgumentException("Supply lastEntryEventId and reason MISSED_EXIT");
    }
    String correlationId = CorrelationIdFilter.currentId();
    if (!corrections.correctMissedExit(request.lastEntryEventId(), site, stationId, actor, correlationId, clock.now())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That entry is no longer the current presence event");
    }
    return Map.of("status", "CORRECTED", "correlationId", correlationId);
  }

  public record Correction(String lastEntryEventId, String reason) {}
}
