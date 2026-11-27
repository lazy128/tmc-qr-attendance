package tmc.access.api;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.access.domain.Direction;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.shared.EmployeeDirectory;
import tmc.shared.Identity;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class GateIncidentController {
  private static final String REVIEW = "gates:review-incidents";
  private final JdbcAttemptStore attempts;
  private final Identity identity;
  private final PermissionPolicy permissions;
  private final EmployeeDirectory employees;
  private final ClockProvider clock;

  public GateIncidentController(JdbcAttemptStore attempts, Identity identity, PermissionPolicy permissions,
      EmployeeDirectory employees, ClockProvider clock) {
    this.attempts = attempts;
    this.identity = identity;
    this.permissions = permissions;
    this.employees = employees;
    this.clock = clock;
  }

  @GetMapping("/gates/v1/incidents")
  public ResponseEntity<Queue> queue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
    String actor = actor(auth);
    boolean canReview = permissions.permits(actor, REVIEW);
    String station = canReview ? null : identity.assignedStation(actor)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    var incidents = attempts.gateIncidents(station).stream()
        .map(item -> new Incident(item.eventId().value(), item.employeeId(),
            employees.name(item.employeeId()), item.siteId().value(), item.stationId().value(),
            item.direction(), item.scannedAt()))
        .toList();
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Queue(canReview, incidents));
  }

  @PostMapping("/gates/v1/incidents/{id}/review")
  public ResponseEntity<?> review(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @PathVariable String id, @RequestBody Review body) {
    String actor = actor(auth);
    if (!permissions.permits(actor, REVIEW)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (body.passed() == null || body.note() == null || body.note().isBlank() || body.note().strip().length() > 300) {
      throw new IllegalArgumentException("Chọn kết quả đối soát và ghi lý do tối đa 300 ký tự.");
    }
    String correlation = CorrelationIdFilter.currentId();
    if (!attempts.reviewGateIncident(id, body.passed(), body.note().strip(), actor, correlation, clock.now())) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new ApiError("INCIDENT_CHANGED", "Lượt này đã được xử lý hoặc không còn trong danh sách.", correlation, false));
    }
    return ResponseEntity.noContent().build();
  }

  private String actor(String auth) {
    return identity.authenticate(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }

  public record Incident(String eventId, String employeeId, String employeeName, String siteId, String stationId,
      Direction direction, Instant scannedAt) {}
  public record Queue(boolean canReview, List<Incident> incidents) {}
  public record Review(Boolean passed, String note) {}
}
