package tmc.bootstrap;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.api.ApiError;
import tmc.access.domain.Direction;
import tmc.access.domain.port.CredentialReader;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.infrastructure.JdbcAttendanceStore;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.audit.AuditWriter;
import tmc.shared.auth.WorkplaceAccounts;
import tmc.shared.auth.WorkplaceAccounts.Account;
import tmc.shared.auth.WorkplaceAccounts.LoginEvent;
import tmc.shared.auth.WorkplaceAccounts.Role;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;
import tmc.userqr.application.IssueQrToken;

@RestController
@ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "true")
public class WorkplaceController {
  private static final String AUTH = HttpHeaders.AUTHORIZATION;
  static final String DEVICE_COOKIE = "tmc_device";
  private static final Duration DEVICE_COOKIE_LIFETIME = Duration.ofDays(365);
  private final WorkplaceAccounts accounts;
  private final CredentialReader credentials;
  private final IssueQrToken issue;
  private final JdbcStationRegistry stations;
  private final TimesheetService timesheets;
  private final JdbcAttendanceStore attendanceRecords;
  private final TimesheetCalculator calculator;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final boolean secureCookie;
  private final boolean trustLoopbackProxy;
  private final InetAddress trustedProxyIp;
  private final boolean personalSelfEnabled;
  private final int pairsPerDay;

  public WorkplaceController(WorkplaceAccounts accounts, CredentialReader credentials, IssueQrToken issue,
      JdbcStationRegistry stations, TimesheetService timesheets, JdbcAttendanceStore attendanceRecords,
      TimesheetCalculator calculator,
      AuditWriter audit, ClockProvider clock, @Value("${tmc.accounts.cookie-secure:true}") boolean secureCookie,
      @Value("${tmc.accounts.trust-loopback-proxy:false}") boolean trustLoopbackProxy,
      @Value("${tmc.accounts.trusted-proxy-ip:}") String trustedProxyIp,
      @Value("${tmc.checkin.personal-self.enabled:false}") boolean personalSelfEnabled,
      @Value("${tmc.attendance.pairs-per-day:1}") int pairsPerDay) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.issue = issue;
    this.stations = stations;
    this.timesheets = timesheets;
    this.attendanceRecords = attendanceRecords;
    this.calculator = calculator;
    this.audit = audit;
    this.clock = clock;
    this.secureCookie = secureCookie;
    this.trustLoopbackProxy = trustLoopbackProxy;
    this.trustedProxyIp = ipLiteral(trustedProxyIp);
    if (!trustedProxyIp.isBlank() && this.trustedProxyIp == null) {
      throw new IllegalArgumentException("TMC_TRUSTED_PROXY_IP must be an IP address");
    }
    this.personalSelfEnabled = personalSelfEnabled;
    this.pairsPerDay = pairsPerDay;
  }

  @PostMapping("/auth/v1/login")
  public ResponseEntity<Account> login(@RequestBody Login body,
      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
      @CookieValue(value = DEVICE_COOKIE, required = false) String deviceCookie, HttpServletRequest request) {
    String deviceId = browserDeviceId(deviceCookie);
    var login = accounts.login(body.username(), body.password(), describeDevice(userAgent), deviceId, connectionIp(request),
        audit, CorrelationIdFilter.currentId())
        .orElseThrow(() -> new ResponseStatusException(
        HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không hợp lệ. Sau nhiều lần thử sai, hãy đợi một phút."));
    audit(login.account().username(), "SESSION_LOGIN", login.account().username());
    return noStore().header(HttpHeaders.SET_COOKIE, cookie(login.token(), login.lifetime()), deviceCookie(deviceId))
        .body(login.account());
  }

  @PatchMapping("/auth/v1/device-name")
  public ResponseEntity<Void> nameDevice(@RequestHeader(value = AUTH, required = false) String auth,
      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
      @CookieValue(value = DEVICE_COOKIE, required = false) String deviceCookie, @RequestBody DeviceName body) {
    var actor = active(auth);
    String id = accounts.nameCurrentDevice(auth, browserDeviceId(deviceCookie), describeDevice(userAgent), body.name())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập lại."));
    audit(actor.username(), "DEVICE_NAME_UPDATE", actor.username());
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, deviceCookie(id))
        .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
  }

  @GetMapping("/auth/v1/me")
  public ResponseEntity<Account> me(@RequestHeader(value = AUTH, required = false) String auth) {
    return noStore().body(session(auth));
  }

  @PostMapping("/auth/v1/logout")
  public ResponseEntity<Void> logout(@RequestHeader(value = AUTH, required = false) String auth) {
    accounts.logout(auth);
    return clearSession();
  }

  @PostMapping("/auth/v1/sessions/close-others")
  public ResponseEntity<ClosedSessions> closeOtherSessions(@RequestHeader(value = AUTH, required = false) String auth) {
    var actor = active(auth);
    var count = accounts.closeOtherSessions(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
        "Phiên hiện tại đã kết thúc. Vui lòng đăng nhập lại."));
    if (count > 0) audit(actor.username(), "SESSION_CLOSE_OTHERS", actor.username());
    return noStore().body(new ClosedSessions(count));
  }

  @PostMapping("/auth/v1/password")
  public ResponseEntity<Void> password(@RequestHeader(value = AUTH, required = false) String auth,
      @RequestBody Password body) {
    var actor = session(auth).username();
    if (!accounts.changePassword(actor, body.current(), body.replacement())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kiểm tra mật khẩu hiện tại và nhập mật khẩu mới khác mật khẩu cũ.");
    }
    audit(actor, "PASSWORD_CHANGE", actor);
    return clearSession();
  }

  @GetMapping("/workplace/v1/me/employee")
  public ResponseEntity<Employee> employee(@RequestHeader(value = AUTH, required = false) String auth) {
    return noStore().body(employee(active(auth)));
  }

  @GetMapping("/workplace/v1/me/attendance")
  public ResponseEntity<Attendance> attendance(@RequestHeader(value = AUTH, required = false) String auth) {
    return noStore().body(attendanceFor(employee(active(auth)).id(), clock.now()));
  }

  @GetMapping("/workplace/v1/me/activity")
  public ResponseEntity<EmployeeActivity> activity(@RequestHeader(value = AUTH, required = false) String auth) {
    var employee = employee(active(auth));
    var login = accounts.loginActivity(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    var scans = attendanceRecords.recentAcceptedForEmployee(employee.id()).stream().map(record -> {
      var name = stations.find(record.stationId()).map(station -> station.gate().name()).orElse(record.stationId());
      return new ScanActivity(record.eventId(), record.scannedAt(), record.siteId(), name, record.direction(), record.suspicious());
    }).toList();
    return noStore().body(new EmployeeActivity(employee.id(), login.otherSessions(), login.recentLogins(),
        login.currentDeviceName(), scans));
  }

  @GetMapping("/workplace/v1/config")
  public ResponseEntity<Config> config() {
    return noStore().body(new Config(personalSelfEnabled));
  }

  @GetMapping("/workplace/v1/me/devices")
  public ResponseEntity<DeviceList> myDevices(@RequestHeader(value = AUTH, required = false) String auth,
      @CookieValue(value = DEVICE_COOKIE, required = false) String device) {
    var actor = active(auth);
    employee(actor);
    return noStore().body(new DeviceList(accounts.trustedDevice(auth, device), accounts.devices(actor.username(), device)));
  }

  @PatchMapping("/workplace/v1/me/devices/{reference}")
  public ResponseEntity<?> changeMyDevice(@RequestHeader(value = AUTH, required = false) String auth,
      @CookieValue(value = DEVICE_COOKIE, required = false) String device,
      @PathVariable String reference, @RequestBody DeviceTrust body) {
    var actor = active(auth);
    employee(actor);
    return changeDevice(auth, device, actor.username(), reference, body.trusted());
  }

  @GetMapping("/workplace/v1/hr/employees/{employeeId}/devices")
  public ResponseEntity<DeviceList> employeeDevices(@RequestHeader(value = AUTH, required = false) String auth,
      @PathVariable String employeeId) {
    return noStore().body(new DeviceList(true, accounts.devices(hrEmployeeUsername(auth, employeeId), null)));
  }

  @PatchMapping("/workplace/v1/hr/employees/{employeeId}/devices/{reference}")
  public ResponseEntity<?> changeEmployeeDevice(@RequestHeader(value = AUTH, required = false) String auth,
      @PathVariable String employeeId, @PathVariable String reference, @RequestBody DeviceTrust body) {
    return changeDevice(auth, null, hrEmployeeUsername(auth, employeeId), reference, body.trusted());
  }

  private String hrEmployeeUsername(String auth, String employeeId) {
    var actor = active(auth);
    if (actor.role() != Role.HR && actor.role() != Role.ADMIN) throw forbidden();
    return accounts.employeeUsername(employeeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private ResponseEntity<?> changeDevice(String auth, String device, String username, String reference, boolean trusted) {
    return switch (accounts.changeTrust(auth, device, username, reference, trusted, audit, CorrelationIdFilter.currentId())) {
      case OK -> ResponseEntity.noContent().build();
      case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("DEVICE_NOT_TRUSTED",
          "Chỉ thiết bị tin cậy hoặc Nhân sự được duyệt thiết bị.", CorrelationIdFilter.currentId(), false));
      case NOT_FOUND -> ResponseEntity.notFound().build();
      case LIMIT -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("DEVICE_LIMIT",
          "Đã có 3 thiết bị tin cậy. Hãy gỡ một thiết bị trước khi duyệt thêm.", CorrelationIdFilter.currentId(), false));
    };
  }

  @PostMapping("/workplace/v1/me/qr")
  public ResponseEntity<?> qr(@RequestHeader(value = AUTH, required = false) String auth,
      @RequestBody(required = false) QrRequest body) {
    var actor = active(auth);
    var employee = employee(actor);
    var direction = body == null ? null : body.direction();
    if (direction != null && !personalSelfEnabled) return ResponseEntity.notFound().build();
    var attendance = attendanceFor(employee.id(), clock.now());
    if ("LEFT".equals(attendance.status())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("SHIFT_COMPLETED",
          "Ngày công đã kết thúc. Mã QR mới sẽ có ở ngày công tiếp theo.", CorrelationIdFilter.currentId(), false));
    }
    String id = accounts.credentialId(employee.id()).orElseThrow();
    var credential = credentials.getCredential(id).orElseThrow();
    if (!credential.active()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("REVOKED", "Thẻ đã thu hồi",
          CorrelationIdFilter.currentId(), false));
    }
    var token = direction == null ? issue.issue(id, credential.deviceHash())
        : issue.issuePersonal(id, credential.deviceHash(), direction);
    // A request racing with session revocation must not return a newly rotated credential.
    active(auth);
    audit(actor.username(), "QR_ISSUE", id);
    return noStore().body(new Pass(employee.id(), token.token(), token.expiresAt(), attendance.status(),
        attendance.lastEntry(), attendance.lastExit(), attendance.workDate()));
  }

  @GetMapping("/workplace/v1/station")
  public ResponseEntity<JdbcStationRegistry.Station> station(@RequestHeader(value = AUTH, required = false) String auth) {
    var actor = active(auth);
    if (actor.role() != Role.GUARD) throw forbidden();
    return noStore().body(stations.find(actor.stationId()).filter(station -> station.status() == JdbcStationRegistry.Status.ACTIVE)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Trạm được giao hiện không hoạt động.")));
  }

  @GetMapping("/workplace/v1/accounts")
  public ResponseEntity<List<Account>> accounts(@RequestHeader(value = AUTH, required = false) String auth) {
    administrator(auth);
    return noStore().body(accounts.accounts());
  }

  @PostMapping("/workplace/v1/accounts")
  @Transactional
  public ResponseEntity<Account> create(@RequestHeader(value = AUTH, required = false) String auth,
      @RequestBody WorkplaceAccounts.NewAccount body) {
    var actor = administrator(auth);
    if ((body.role() == Role.EMPLOYEE || body.role() == Role.MANAGER)
        && !stations.supports(body.siteId())) {
      throw new IllegalArgumentException("Chọn Bình Dương hoặc Long An cho nhân viên.");
    }
    if (body.role() == Role.GUARD && (body.stationId() == null || stations.find(body.stationId()).isEmpty())) {
      throw new IllegalArgumentException("Chọn trạm thuộc nhà máy được quản lý.");
    }
    try {
      var created = accounts.create(body);
      audit(actor.username(), "ACCOUNT_CREATE", created.username());
      return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(created);
    } catch (DuplicateKeyException duplicate) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Tên đăng nhập hoặc mã nhân viên đã tồn tại.");
    }
  }

  @PatchMapping("/workplace/v1/accounts/{username}")
  @Transactional
  public ResponseEntity<Account> update(@RequestHeader(value = AUTH, required = false) String auth,
      @PathVariable String username, @RequestBody AccountUpdate body) {
    var actor = administrator(auth);
    if (body.enabled() == null && body.password() == null) throw new IllegalArgumentException("Chưa có thay đổi.");
    var updated = accounts.update(actor.username(), username, body.enabled(), body.password())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản."));
    audit(actor.username(), body.password() != null ? "ACCOUNT_PASSWORD_RESET" : "ACCOUNT_STATUS", username);
    return noStore().body(updated);
  }

  private Account session(String auth) {
    return accounts.session(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập."));
  }
  private Account active(String auth) {
    var actor = session(auth);
    if (actor.changeRequired()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Hãy đổi mật khẩu trước khi tiếp tục.");
    return actor;
  }
  private Account administrator(String auth) {
    var actor = active(auth);
    if (actor.role() != Role.ADMIN) throw forbidden();
    return actor;
  }
  private Employee employee(Account actor) {
    if (actor.employeeId() == null) throw forbidden();
    return accounts.find(actor.employeeId()).orElseThrow();
  }
  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không có quyền thực hiện thao tác này.");
  }
  private Attendance attendanceFor(String id, Instant now) {
    var date = calculator.workDateFor(id, now);
    var day = timesheets.calculate(id, date);
    String status = day.map(data -> data.unpairedEntry() != null ? "IN_SHIFT"
        : data.completedPairs() >= pairsPerDay ? "LEFT"
        : data.row().lastOut() != null ? "BETWEEN_PAIRS" : "NOT_ENTERED").orElse("NOT_ENTERED");
    return new Attendance(id, status, day.map(TimesheetCalculator.Calculation::lastEntry).orElse(null),
        day.map(data -> data.row().lastOut()).orElse(null), date);
  }
  private String connectionIp(HttpServletRequest request) {
    var peer = ipLiteral(request.getRemoteAddr());
    if (peer != null && ((trustLoopbackProxy && peer.isLoopbackAddress()) || peer.equals(trustedProxyIp))) {
      var socketIp = ipLiteral(request.getHeader("X-TMC-Client-IP"));
      if (socketIp != null) return socketIp.getHostAddress();
    }
    return peer == null ? null : peer.getHostAddress();
  }

  private static InetAddress ipLiteral(String value) {
    if (value == null || value.length() > 45) return null;
    boolean ipv4 = value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    boolean ipv6 = value.contains(":") && value.matches("[0-9A-Fa-f:.]+");
    if (!ipv4 && !ipv6) return null;
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException invalid) {
      return null;
    }
  }
  private String cookie(String value, Duration lifetime) {
    return cookie(WorkplaceConfiguration.SESSION_COOKIE, value, lifetime);
  }
  private String deviceCookie(String id) {
    return cookie(DEVICE_COOKIE, id, DEVICE_COOKIE_LIFETIME);
  }
  private String cookie(String name, String value, Duration lifetime) {
    return ResponseCookie.from(name, value).httpOnly(true).secure(secureCookie)
        .sameSite("Strict").path("/").maxAge(lifetime).build().toString();
  }
  private static String browserDeviceId(String cookie) {
    return cookie != null && cookie.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        ? cookie : UUID.randomUUID().toString();
  }
  private ResponseEntity<Void> clearSession() {
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
  }
  private static ResponseEntity.BodyBuilder noStore() { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store"); }
  private void audit(String actor, String action, String target) {
    audit.append(actor, action, target, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
  }

  private static String describeDevice(String userAgent) {
    if (userAgent == null) return "Thiết bị chưa xác định";
    String agent = userAgent.toLowerCase(Locale.ROOT);
    String device = agent.contains("iphone") ? "iPhone" : agent.contains("ipad") ? "iPad"
        : agent.contains("android") ? "Android" : agent.contains("windows") ? "Windows"
        : agent.contains("macintosh") ? "macOS" : agent.contains("linux") ? "Linux" : "Thiết bị chưa xác định";
    String browser = agent.contains("edg") ? "Edge" : agent.contains("chrome") || agent.contains("crios") ? "Chrome"
        : agent.contains("firefox") || agent.contains("fxios") ? "Firefox" : agent.contains("safari") ? "Safari" : "";
    return browser.isEmpty() ? device : device + " · " + browser;
  }

  public record Login(String username, String password) {}
  public record DeviceName(String name) {}
  public record Config(boolean personalSelfEnabled) {}
  public record DeviceTrust(boolean trusted) {}
  public record DeviceList(boolean canManage, List<WorkplaceAccounts.BrowserDevice> devices) {}
  public record QrRequest(Direction direction) {}
  public record Password(String current, String replacement) {}
  public record AccountUpdate(Boolean enabled, String password) {}
  public record ClosedSessions(int closed) {}
  public record ScanActivity(String eventId, Instant scannedAt, String siteId, String stationName, String direction,
      boolean suspicious) {}
  public record EmployeeActivity(String employeeId, int otherSessions, List<LoginEvent> recentLogins,
      String currentDeviceName, List<ScanActivity> scans) {}
  public record Attendance(String employeeId, String status, Instant lastEntry, Instant lastExit, LocalDate workDate) {}
  public record Pass(String employeeId, String token, Instant expiresAt, String status, Instant lastEntry, Instant lastExit,
      LocalDate workDate) {}
}
