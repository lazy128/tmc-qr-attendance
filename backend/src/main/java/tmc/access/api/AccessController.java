package tmc.access.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tmc.access.application.VerifyAccessUseCase;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

@RestController
public class AccessController {
  private final VerifyAccessUseCase usecase;

  public AccessController(VerifyAccessUseCase usecase) {
    this.usecase = usecase;
  }

  @PostMapping("/access/v1/verify")
  public ResponseEntity<?> verify(
      @RequestHeader(value = "X-Correlation-Id", required = false) String correlation,
      @RequestHeader(value = "X-Station-Id") String station,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody VerifyBody body
  ) {
    require(body.eventId(), 100, "eventId");
    require(body.siteId(), 100, "siteId");
    require(station, 100, "X-Station-Id");
    require(body.direction(), 5, "direction");
    require(body.scannedAt(), 40, "scannedAt");
    require(body.token(), 4096, "token");
    require(body.deviceId(), 256, "deviceId");
    if (correlation != null) require(correlation, 100, "X-Correlation-Id");
    if (idempotencyKey != null && !idempotencyKey.equals(body.eventId())) {
      return ResponseEntity.badRequest().body(new ApiError(
          "IDEMPOTENCY_KEY_MISMATCH",
          "Idempotency-Key must equal eventId",
          correlation,
          false
      ));
    }
    String correlationId = correlation == null ? UUID.randomUUID().toString() : correlation;
    var request = new AccessRequest(
        new EventId(body.eventId()),
        correlationId,
        new SiteId(body.siteId()),
        new StationId(station),
        Direction.valueOf(body.direction()),
        scanTime(body.scannedAt()),
        body.token(),
        body.deviceId()
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
    String kiosk = attempt.command() == Command.UNKNOWN
        ? "đang xác minh trạng thái cổng, chờ bảo vệ"
        : attempt.decision() == Decision.DENY
            ? attempt.reasonCode() == Reason.SNAPSHOT_STALE
                ? "Chờ bảo vệ hỗ trợ"
                : "Từ chối: " + attempt.reasonCode()
            : attempt.command() == Command.FAILED
                ? "Cổng chưa mở, chờ bảo vệ hỗ trợ"
                : "Đã ghi nhận yêu cầu";
    return ResponseEntity.ok(new VerifyResponse(
        attempt.eventId().value(),
        attempt.decision().name(),
        attempt.reasonCode() == null ? null : attempt.reasonCode().name(),
        attempt.command().name(),
        attempt.passage().name(),
        attempt.evidence() == null ? null : attempt.evidence().name(),
        kiosk,
        correlationId,
        result.steps()
    ));
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
      List<String> steps
  ) {}
}
