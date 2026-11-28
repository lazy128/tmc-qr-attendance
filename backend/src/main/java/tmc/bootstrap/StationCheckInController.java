package tmc.bootstrap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.api.ApiError;
import tmc.access.application.VerifyAccessUseCase;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.AccessStates.Channel;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.TokenValidator;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.access.infrastructure.persistence.JdbcStationRegistry.Station;
import tmc.attendance.application.TimesheetCalculator;
import tmc.shared.StaffDirectory;
import tmc.shared.WorkplaceSites;
import tmc.shared.auth.StationQrTokens;
import tmc.shared.audit.AuditWriter;
import tmc.shared.auth.WorkplaceAccounts;
import tmc.shared.auth.WorkplaceAccounts.Account;
import tmc.shared.auth.WorkplaceAccounts.Role;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;
import tmc.userqr.application.IssueQrToken;

@RestController
@ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "true")
public class StationCheckInController {
  private final WorkplaceAccounts accounts;
  private final JdbcStationRegistry stations;
  private final StationQrTokens stationQr;
  private final CredentialReader credentials;
  private final IssueQrToken employeeQr;
  private final TokenValidator tokens;
  private final VerifyAccessUseCase verify;
  private final AttemptStore attempts;
  private final ClockProvider clock;
  private final TimesheetCalculator calculator;
  private final JdbcTemplate jdbc;
  private final AuditWriter audit;

  public StationCheckInController(WorkplaceAccounts accounts, JdbcStationRegistry stations, StationQrTokens stationQr,
      CredentialReader credentials, IssueQrToken employeeQr, TokenValidator tokens, VerifyAccessUseCase verify,
      AttemptStore attempts, ClockProvider clock, TimesheetCalculator calculator, JdbcTemplate jdbc, AuditWriter audit) {
    this.accounts = accounts;
    this.stations = stations;
    this.stationQr = stationQr;
    this.credentials = credentials;
    this.employeeQr = employeeQr;
    this.tokens = tokens;
    this.verify = verify;
    this.attempts = attempts;
    this.clock = clock;
    this.calculator = calculator;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  @PostMapping("/workplace/v1/stations/{stationId}/qr/{direction}")
  public ResponseEntity<?> code(@PathVariable String stationId, @PathVariable Direction direction,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
    var account = active(auth);
    if (account.role() != Role.ADMIN && (account.role() != Role.GUARD || !stationId.equals(account.stationId()))) {
      return error(HttpStatus.FORBIDDEN, "STATION_SCOPE", "Bạn không có quyền hiển thị mã tại cổng này.", false);
    }
    var station = usableStation(stationId, direction);
    if (station == null) {
      return error(HttpStatus.CONFLICT, "STATION_UNAVAILABLE", "Cổng chấm công này đang tắt hoặc không hỗ trợ hướng đã chọn.", false);
    }
    jdbc.queryForObject("SELECT username FROM workplace_account WHERE username=? FOR UPDATE", String.class, account.username());
    active(auth);
    var now = clock.now();
    long recent = jdbc.queryForObject("""
        SELECT COUNT(*) FROM edge_audit WHERE actor=? AND action='STATION_QR_ISSUED' AND occurred_at>?
        """, Long.class, account.username(), now.minusSeconds(60).atOffset(java.time.ZoneOffset.UTC));
    if (recent >= 30) {
      return error(HttpStatus.TOO_MANY_REQUESTS, "STATION_QR_RATE_LIMIT", "Cổng đang làm mới mã quá thường xuyên. Hãy thử lại sau.", true);
    }
    var issued = stationQr.issue(station.stationId(), station.siteId(), direction.name(), now);
    audit.append(account.username(), "STATION_QR_ISSUED", stationId + "/" + direction.name(), "SUCCESS",
        CorrelationIdFilter.currentId(), now);
    return ok(issued);
  }

  @PostMapping("/workplace/v1/me/check-in")
  public ResponseEntity<?> checkIn(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @CookieValue(value = WorkplaceController.DEVICE_COOKIE, required = false) String device, @RequestBody Scan body) {
    var employee = employee(auth);
    if (!accounts.trustedDevice(auth, device)) return untrusted();
    var now = clock.now();
    var proof = stationQr.verify(body.code(), now).orElse(null);
    if (proof == null) return error(HttpStatus.BAD_REQUEST, "INVALID_STATION_QR", "Hãy quét mã QR tại cổng chấm công.", false);
    if (!StaffDirectory.siteFor(employee.siteId()).equals(proof.siteId())) {
      return error(HttpStatus.FORBIDDEN, "STATION_SCOPE", "Mã này thuộc nhà máy khác.", false);
    }
    var direction = Direction.valueOf(proof.direction());
    String scanId = proof.id() + ":" + employee.id();
    String eventId = eventId(scanId);
    var previous = attempts.findAttempt(eventId)
        .filter(attempt -> attempt.stationId().value().equals(proof.stationId()) && attempt.direction() == direction);
    if (previous.isPresent()) return response(previous.get());
    if (!proof.expiresAt().isAfter(now)) {
      return error(HttpStatus.CONFLICT, "TOKEN_EXPIRED", "Mã QR tại cổng đã hết hạn. Hãy quét mã đang hiển thị.", false);
    }
    var station = usableStation(proof.stationId(), direction);
    if (station == null || !station.siteId().equals(proof.siteId())) {
      return error(HttpStatus.CONFLICT, "STATION_UNAVAILABLE", "Cổng chấm công này đang tắt hoặc đã đổi cấu hình.", false);
    }
    var credential = accounts.credentialId(employee.id()).flatMap(credentials::getCredential).orElseThrow();
    if (!credential.active()) return error(HttpStatus.FORBIDDEN, "REVOKED", "Thông tin chấm công đã bị thu hồi.", false);
    var issued = employeeQr.issue(credential.id(), credential.deviceHash());
    return record(auth, station, direction, now, eventId, scanId, issued.token(), credential.deviceHash(), Channel.STATION_QR);
  }

  @GetMapping("/workplace/v1/me/check-in/status/{id}")
  ResponseEntity<?> checkInStatus(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @CookieValue(value = WorkplaceController.DEVICE_COOKIE, required = false) String device, @PathVariable String id) {
    var employee = employee(auth);
    if (!accounts.trustedDevice(auth, device)) return untrusted();
    return attempts.findAttempt(id).filter(attempt -> attempt.channel() != Channel.GATE_SCAN
        && employee.id().equals(attempt.employeeId())).map(this::response)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  ResponseEntity<?> personalCheckIn(String auth, String device, Scan body) {
    var employee = employee(auth);
    if (!accounts.trustedDevice(auth, device)) return untrusted();
    var now = clock.now();
    var claims = personalClaims(body.code());
    if (claims == null) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Mã QR cá nhân không hợp lệ.", false);
    }
    var credential = accounts.credentialId(employee.id()).flatMap(credentials::getCredential).orElseThrow();
    if (!credential.id().equals(claims.credentialId())) {
      return error(HttpStatus.FORBIDDEN, "QR_OWNER_MISMATCH", "Mã QR thuộc tài khoản khác. Đăng nhập cùng tài khoản trên điện thoại và máy tính.", false);
    }
    String scanId = "personal:" + claims.jti() + ":" + employee.id();
    String eventId = eventId(scanId);
    var previous = attempts.findAttempt(eventId);
    if (previous.isPresent()) return response(previous.get());
    if (!claims.expiresAt().isAfter(now)) {
      return error(HttpStatus.CONFLICT, "TOKEN_EXPIRED", "Mã QR đã hết hạn. Quét mã mới đang hiển thị trên máy tính.", false);
    }
    if (!calculator.workDateFor(employee.id(), claims.issuedAt()).equals(calculator.workDateFor(employee.id(), now))) {
      return error(HttpStatus.CONFLICT, "TOKEN_EXPIRED", "Ca mới đã bắt đầu. Hãy quét mã mới trên máy tính.", false);
    }
    if (!credential.active() || credential.version() != claims.version()
        || !Objects.equals(credential.deviceHash(), claims.deviceHash())) {
      return error(HttpStatus.FORBIDDEN, "REVOKED", "Mã QR đã thay đổi hoặc bị thu hồi. Hãy quét mã mới.", false);
    }
    String siteId = StaffDirectory.siteFor(employee.siteId());
    Direction direction = claims.attendanceDirection() == null ? Direction.ENTRY : claims.attendanceDirection();
    var station = WorkplaceSites.ALL.stream().filter(site -> site.id().equals(siteId)).findFirst()
        .map(site -> usableStation(direction == Direction.ENTRY ? site.entryStation() : site.exitStation(), direction))
        .filter(entry -> siteId.equals(entry.siteId())).orElse(null);
    if (station == null) {
      return error(HttpStatus.CONFLICT, "STATION_UNAVAILABLE", "Cổng chấm công của cơ sở đang tạm ngưng theo hướng này.", false);
    }
    return record(auth, station, direction, now, eventId, scanId, body.code(), credential.deviceHash(), Channel.PERSONAL_SELF);
  }

  private Claims personalClaims(String code) {
    if (code == null || code.isBlank() || code.length() > 4096) return null;
    try {
      var claims = tokens.validate(code);
      return "tmc".equals(claims.issuer()) && Channel.PERSONAL_AUDIENCE.equals(claims.audience()) ? claims : null;
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static String eventId(String scanId) {
    return "self-" + UUID.nameUUIDFromBytes(scanId.getBytes(StandardCharsets.UTF_8));
  }

  private ResponseEntity<?> record(String auth, Station station, Direction direction, Instant now,
      String eventId, String scanId, String token, String deviceHash, Channel channel) {
    active(auth);
    // Match the database timestamp precision so the first response and a retry agree.
    Instant scannedAt = now.truncatedTo(ChronoUnit.MICROS);
    var request = new AccessRequest(new EventId(eventId), CorrelationIdFilter.currentId(),
        new SiteId(station.siteId()), new StationId(station.stationId()), direction, scannedAt,
        token, deviceHash, scannedAt, scanId, channel);
    var result = verify.execute(request);
    if (result.inProgress()) {
      return error(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Đang xử lý lượt chấm công.", true);
    }
    if (result.attempt().reasonCode() == Reason.PERSIST_FAILED) {
      return error(HttpStatus.SERVICE_UNAVAILABLE, "PERSIST_FAILED", "Chưa lưu được lượt chấm công. Hãy thử lại.", true);
    }
    return response(result.attempt());
  }

  private Account active(String authorization) {
    var account = accounts.session(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập."));
    if (account.changeRequired()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Hãy đổi mật khẩu trước khi tiếp tục.");
    return account;
  }

  private StaffDirectory.Employee employee(String authorization) {
    var actor = active(authorization);
    if (actor.employeeId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Đăng nhập tài khoản nhân viên để chấm công.");
    }
    return accounts.find(actor.employeeId()).orElseThrow();
  }

  private Station usableStation(String stationId, Direction direction) {
    return stations.find(stationId).filter(station -> station.status() == JdbcStationRegistry.Status.ACTIVE
        && station.gate().attendance() && station.gate().directions().contains(direction)).orElse(null);
  }

  private ResponseEntity<?> response(AccessAttempt attempt) {
    if (attempt.reasonCode() == Reason.PERSONAL_SELF_LIMIT) {
      return error(HttpStatus.TOO_MANY_REQUESTS, "PERSONAL_SELF_LIMIT",
          "Bạn đã đạt giới hạn lượt tự khai trong ngày công này.", false);
    }
    return ok(new Result(attempt.eventId().value(), attempt.direction().name(), attempt.decision().name(),
        attempt.reasonCode() == null ? null : attempt.reasonCode().name(), attempt.command().name(), attempt.evidence() == null ? null : attempt.evidence().name(), attempt.scannedAt()));
  }

  private static ResponseEntity<ApiError> untrusted() {
    return error(HttpStatus.FORBIDDEN, "DEVICE_NOT_TRUSTED", "Hãy duyệt trình duyệt này từ thiết bị tin cậy hoặc nhờ Nhân sự duyệt.", false);
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
  }

  private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, boolean retryable) {
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new ApiError(code, message, CorrelationIdFilter.currentId(), retryable));
  }

  public record Scan(String code) {}
  public record Result(String eventId, String direction, String decision, String reasonCode, String command, String evidence, Instant scannedAt) {}
}
