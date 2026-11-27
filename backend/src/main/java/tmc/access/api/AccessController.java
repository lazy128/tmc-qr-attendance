package tmc.access.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.VerifyAccessUseCase;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.AttemptStore;
import tmc.shared.web.CorrelationIdFilter;
import tmc.shared.EmployeeDirectory;
import tmc.shared.Identity;
import tmc.shared.clock.ClockProvider;

@RestController
public class AccessController {
  private static final Logger log = LoggerFactory.getLogger(AccessController.class);
  private final VerifyAccessUseCase usecase;
  private final MeterRegistry metrics;
  private final Identity identity;
  private final EmployeeDirectory employees;
  private final ClockProvider clock;
  private final boolean workplace;
  private final AttemptStore attempts;

  public AccessController(VerifyAccessUseCase usecase, MeterRegistry metrics, Identity identity,
      EmployeeDirectory employees, ClockProvider clock, AttemptStore attempts,
      @Value("${tmc.accounts.enabled:false}") boolean workplace) {
    this.usecase = usecase;
    this.metrics = metrics;
    this.identity = identity;
    this.employees = employees;
    this.clock = clock;
    this.workplace = workplace;
    this.attempts = attempts;
  }

  @PostMapping("/access/v1/verify")
  public ResponseEntity<?> verify(
      @RequestHeader(value = "X-Station-Id") String station,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody VerifyBody body
  ) {
    if (workplace) authorizeStation(auth, station);
    require(body.eventId(), 100, "eventId");
    require(body.siteId(), 100, "siteId");
    require(station, 100, "X-Station-Id");
    require(body.direction(), 5, "direction");
    require(body.scannedAt(), 40, "scannedAt");
    require(body.token(), 4096, "token");
    require(body.deviceId(), 256, "deviceId");
    String correlationId = CorrelationIdFilter.currentId();
    if (idempotencyKey != null && !idempotencyKey.equals(body.eventId())) {
      return ResponseEntity.badRequest().body(new ApiError(
          "IDEMPOTENCY_KEY_MISMATCH",
          "Idempotency-Key must equal eventId",
          correlationId,
          false
      ));
    }
    Instant submittedAt = scanTime(body.scannedAt());
    var request = new AccessRequest(
        new EventId(body.eventId()),
        correlationId,
        new SiteId(body.siteId()),
        new StationId(station),
        Direction.valueOf(body.direction()),
        workplace ? clock.now() : submittedAt,
        body.token(),
        body.deviceId(),
        submittedAt
    );
    var result = usecase.execute(request);
    if (result.inProgress()) {
      return ResponseEntity.status(409).body(new ApiError(
          "REQUEST_IN_PROGRESS",
          "Request is still processing",
          correlationId,
          true
      ));
    }
    var attempt = result.attempt();
    if (workplace && attempt.reasonCode() == Reason.PERSIST_FAILED) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("PERSIST_FAILED",
          "Chưa thể lưu lượt quét. Hệ thống sẽ thử lại cùng mã lượt.", correlationId, true));
    }
    String decision = attempt.decision().name();
    String reason = attempt.reasonCode() == null ? decision : attempt.reasonCode().name();
    metrics.counter("tmc.edge.verify.decisions", "decision", decision, "reasonCode", reason).increment();
    log.info("verify completed decision={} reasonCode={} command={}", decision, reason, attempt.command());
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(response(attempt, result.steps()));
  }

  @GetMapping("/access/v1/scans/{id}")
  public ResponseEntity<VerifyResponse> scan(@PathVariable String id, @RequestHeader("X-Station-Id") String station,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    if (!workplace) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    authorizeStation(auth, station);
    require(id, 100, "eventId");
    var attempt = attempts.findAttempt(id).filter(item -> item.stationId().value().equals(station))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(response(attempt, List.of()));
  }

  private void authorizeStation(String auth, String station) {
    var actor = identity.authenticate(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
        "Đăng nhập tài khoản bảo vệ để xác minh lượt quét."));
    if (identity.assignedStation(actor).filter(station::equals).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Lượt quét phải đi qua trạm được giao.");
    }
  }

  private VerifyResponse response(AccessAttempt attempt, List<String> steps) {
    String kiosk = attempt.command() == Command.EXPIRED
        ? "Lượt cổng đã chuyển để Nhân sự đối soát"
        : attempt.command() == Command.UNKNOWN
        ? "đang xác minh trạng thái cổng, chờ bảo vệ"
        : attempt.decision() == Decision.DENY
            ? attempt.reasonCode() == Reason.SNAPSHOT_STALE
                ? "Chờ bảo vệ hỗ trợ"
                : "Từ chối: " + attempt.reasonCode()
            : attempt.command() == Command.FAILED
                ? "Cổng chưa mở, chờ bảo vệ hỗ trợ"
                : "Đã ghi nhận yêu cầu";
    return new VerifyResponse(
        attempt.eventId().value(),
        attempt.decision().name(),
        attempt.reasonCode() == null ? null : attempt.reasonCode().name(),
        attempt.command().name(),
        attempt.passage().name(),
        attempt.evidence() == null ? null : attempt.evidence().name(),
        kiosk,
        CorrelationIdFilter.currentId(),
        steps,
        attempt.decision() == Decision.ALLOW && (attempt.command() == Command.ACKED
            || attempt.evidence() == tmc.access.domain.AccessStates.Evidence.MANUAL_PASSAGE)
            ? employees.name(attempt.employeeId()) : null,
        attempt.scannedAt(),
        attempt.attendanceGate()
    );
  }

  private static void require(String value, int max, String field) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException("Missing or invalid " + field);
    }
  }

  private static Instant scanTime(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("scannedAt must be an ISO-8601 instant");
    }
  }

  public record VerifyBody(
      String eventId,
      String siteId,
      String direction,
      String scannedAt,
      String token,
      String deviceId
  ) {}

  public record VerifyResponse(
      String eventId,
      String decision,
      String reasonCode,
      String command,
      String passage,
      String evidence,
      String kioskMessage,
      String correlationId,
      List<String> steps,
      String employeeName,
      Instant scannedAt,
      boolean attendanceGate
  ) {}
}
