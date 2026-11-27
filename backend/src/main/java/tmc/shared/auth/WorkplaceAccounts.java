package tmc.shared.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tmc.shared.EmployeeDirectory;
import tmc.shared.Identity;
import tmc.shared.StaffDirectory;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.StaffDirectory.Group;
import tmc.shared.clock.ClockProvider;
import tmc.shared.audit.AuditWriter;

public final class WorkplaceAccounts implements Identity, EmployeeDirectory {
  public enum Role { EMPLOYEE, MANAGER, HR, GUARD, ADMIN }

  private static final String ACCOUNT_COLUMNS = """
      SELECT a.*, e.full_name, COALESCE(e.site_id, g.site_id) AS account_site FROM workplace_account a
      LEFT JOIN workplace_employee e ON e.employee_id = a.employee_id
      LEFT JOIN station_registry g ON g.station_id = a.station_id
      """;
  private static final RowMapper<Account> ACCOUNT = (rs, i) -> new Account(rs.getString("username"),
      Optional.ofNullable(rs.getString("full_name")).orElse(rs.getString("username")),
      Role.valueOf(rs.getString("role")), rs.getString("employee_id"), rs.getString("station_id"),
      StaffDirectory.siteFor(rs.getString("account_site")), rs.getBoolean("enabled"), rs.getBoolean("change_required"));
  private static final String EMPLOYEES = """
      SELECT e.*, c.active FROM workplace_employee e JOIN credential c ON e.credential_id = c.credential_id
      """;
  private static final RowMapper<Employee> EMPLOYEE = (rs, i) -> new Employee(rs.getString("employee_id"),
      rs.getString("full_name"), Group.valueOf(rs.getString("access_group")), rs.getString("department_id"),
      rs.getString("shift_id"), rs.getString("site_id"), rs.getBoolean("active"));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ClockProvider clock;
  private final SessionTokens tokens;
  private final Duration sessionLifetime;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String absentHash = passwords.encode(UUID.randomUUID().toString());

  public WorkplaceAccounts(JdbcTemplate jdbc, TransactionTemplate tx, ClockProvider clock, String secret,
      Duration sessionLifetime) {
    if (sessionLifetime.isNegative() || sessionLifetime.isZero()) throw new IllegalArgumentException("Invalid session lifetime");
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.tokens = new SessionTokens(secret);
    this.sessionLifetime = sessionLifetime;
  }

  public Optional<Login> login(String rawUsername, String password, String deviceLabel, String deviceId, String clientIp,
      AuditWriter audit, String correlation) {
    if (rawUsername == null || !validPasswordLength(password, 1)) return Optional.empty();
    String username = rawUsername.toLowerCase(Locale.ROOT).strip();
    if (!username.matches("[a-z0-9][a-z0-9._-]{2,59}")) return Optional.empty();
    return tx.execute(status -> {
      var authentication = jdbc.query("SELECT * FROM workplace_account WHERE username=? FOR UPDATE", rs -> {
        if (!rs.next()) return null;
        return new LoginRecord(rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getInt("failed_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class), rs.getInt("security_version"),
            rs.getBoolean("device_trust_initialized"));
      }, username);
      var now = clock.now();
      if (authentication == null || !authentication.enabled()) {
        passwords.matches(password, absentHash);
        return Optional.empty();
      }
      var lock = authentication.lockedUntil();
      if (lock != null && lock.toInstant().isAfter(now)) return Optional.empty();
      if (!passwords.matches(password, authentication.hash())) {
        int failures = lock != null ? 1 : authentication.failures() + 1;
        jdbc.update("UPDATE workplace_account SET failed_attempts=?, locked_until=? WHERE username=?", failures,
            failures >= 5 ? utc(now.plusSeconds(60)) : null, username);
        return Optional.empty();
      }
      jdbc.update("UPDATE workplace_account SET failed_attempts=0, locked_until=NULL WHERE username=?", username);
      jdbc.update("DELETE FROM workplace_session WHERE expires_at<=?", utc(now));
      var expiry = now.plus(sessionLifetime).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      var id = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO workplace_session VALUES (?,?,?,?)", id, username, authentication.version(), utc(expiry));
      rememberDevice(username, deviceId);
      if (!authentication.trustInitialized() && account(username).orElseThrow().employeeId() != null) {
        jdbc.update("UPDATE workplace_account SET device_trust_initialized=TRUE WHERE username=?", username);
        jdbc.update("UPDATE workplace_browser_device SET trusted=TRUE WHERE username=? AND device_id=?", username, deviceId);
        audit.append(username, "DEVICE_TRUST_GRANTED", username + "/" + deviceCode(deviceId), "SUCCESS", correlation, now);
      }
      jdbc.update("""
          INSERT INTO workplace_login_history(session_id,username,signed_in_at,device_label,device_id,client_ip)
          VALUES (?,?,?,?,?,?)
          """, id, username, utc(now), deviceLabel, deviceId, clientIp);
      return Optional.of(new Login(account(username).orElseThrow(), tokens.issue(username, id, now, expiry), sessionLifetime));
    });
  }

  public Optional<Account> session(String authorization) {
    var now = clock.now();
    return tokens.verify(authorization, now).flatMap(token -> jdbc.query(ACCOUNT_COLUMNS + """
        JOIN workplace_session s ON s.username=a.username AND s.security_version=a.security_version
        WHERE a.username=? AND s.session_id=? AND s.expires_at>? AND a.enabled=TRUE
        """, ACCOUNT, token.username(), token.id(), utc(now)).stream().findFirst());
  }

  @Override public Optional<String> authenticate(String authorization) {
    return session(authorization).filter(account -> !account.changeRequired()).map(Account::username);
  }

  @Override public Optional<String> assignedStation(String actor) {
    return account(actor).filter(user -> user.enabled() && user.role() == Role.GUARD).map(Account::stationId);
  }

  public void logout(String authorization) {
    tokens.verify(authorization, clock.now()).ifPresent(token -> jdbc.update(
        "DELETE FROM workplace_session WHERE session_id=? AND username=?", token.id(), token.username()));
  }

  public Optional<LoginActivity> loginActivity(String authorization) {
    var now = clock.now();
    return tokens.verify(authorization, now).map(token -> {
      int others = jdbc.queryForObject("""
          SELECT COUNT(*) FROM workplace_session WHERE username=? AND session_id<>? AND expires_at>?
          """, Integer.class, token.username(), token.id(), utc(now));
      var recent = jdbc.query("""
          SELECT h.session_id, h.signed_in_at, h.device_label, h.device_id, h.client_ip, d.display_name,
                 s.session_id IS NOT NULL AS active
          FROM workplace_login_history h LEFT JOIN workplace_session s ON s.session_id=h.session_id AND s.expires_at>?
          LEFT JOIN workplace_browser_device d ON d.username=h.username AND d.device_id=h.device_id
          WHERE h.username=? ORDER BY h.signed_in_at DESC,h.session_id DESC LIMIT 10
          """, (row, index) -> new LoginEvent(row.getObject("signed_in_at", OffsetDateTime.class).toInstant(),
          row.getString("device_label"), row.getString("display_name"), deviceCode(row.getString("device_id")),
          row.getString("client_ip"),
          token.id().equals(row.getString("session_id")), row.getBoolean("active")),
          utc(now), token.username());
      var name = jdbc.query("""
          SELECT d.display_name FROM workplace_login_history h
          JOIN workplace_browser_device d ON d.username=h.username AND d.device_id=h.device_id
          WHERE h.username=? AND h.session_id=?
          """, row -> row.next() ? row.getString(1) : null, token.username(), token.id());
      return new LoginActivity(others, recent, name);
    });
  }

  public Optional<String> nameCurrentDevice(String authorization, String candidateId, String deviceLabel, String name) {
    String label = input(name, "tên thiết bị", 60);
    if (label.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Tên thiết bị chứa ký tự không hợp lệ.");
    }
    return tokens.verify(authorization, clock.now()).flatMap(token -> tx.execute(status -> {
      jdbc.queryForObject("SELECT username FROM workplace_account WHERE username=? FOR UPDATE", String.class, token.username());
      if (session(authorization).filter(account -> !account.changeRequired()).isEmpty()) return Optional.empty();
      String boundId = jdbc.query("SELECT device_id FROM workplace_login_history WHERE session_id=? AND username=?",
          row -> row.next() ? row.getString(1) : null, token.id(), token.username());
      if (boundId != null && !boundId.equals(candidateId)) return Optional.empty();
      String deviceId = candidateId;
      rememberDevice(token.username(), deviceId);
      jdbc.update("UPDATE workplace_browser_device SET display_name=? WHERE username=? AND device_id=?",
          label, token.username(), deviceId);
      if (boundId == null) {
        int updated = jdbc.update("UPDATE workplace_login_history SET device_id=? WHERE session_id=? AND username=?",
            deviceId, token.id(), token.username());
        if (updated == 0) {
          jdbc.update("INSERT INTO workplace_login_history(session_id,username,signed_in_at,device_label,device_id) VALUES (?,?,?,?,?)",
              token.id(), token.username(), utc(token.issuedAt()), deviceLabel, deviceId);
        }
      }
      return Optional.of(deviceId);
    }));
  }

  public boolean trustedDevice(String authorization, String deviceId) {
    if (deviceId == null || deviceId.length() != 36) return false;
    var now = clock.now();
    return tokens.verify(authorization, now).map(token -> Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM workplace_session s
          JOIN workplace_account a ON a.username=s.username AND a.security_version=s.security_version
          JOIN workplace_login_history h ON h.session_id=s.session_id AND h.username=a.username
          JOIN workplace_browser_device d ON d.username=a.username AND d.device_id=h.device_id
          WHERE s.session_id=? AND a.username=? AND s.expires_at>? AND a.enabled=TRUE
            AND a.change_required=FALSE AND d.device_id=? AND d.trusted=TRUE)
        """, Boolean.class, token.id(), token.username(), utc(now), deviceId))).orElse(false);
  }

  public Optional<String> employeeUsername(String employeeId) {
    return jdbc.query("SELECT username FROM workplace_account WHERE employee_id=?",
        row -> row.next() ? Optional.of(row.getString(1)) : Optional.empty(), employeeId);
  }

  public List<BrowserDevice> devices(String username, String currentDeviceId) {
    return jdbc.query("""
        SELECT d.device_id, d.display_name, d.trusted,
          (SELECT h.device_label FROM workplace_login_history h
           WHERE h.username=d.username AND h.device_id=d.device_id
           ORDER BY h.signed_in_at DESC LIMIT 1) AS label,
          (SELECT MAX(h.signed_in_at) FROM workplace_login_history h
           WHERE h.username=d.username AND h.device_id=d.device_id) AS last_login
        FROM workplace_browser_device d WHERE d.username=?
        ORDER BY d.trusted DESC, last_login DESC, d.device_id
        """, (row, index) -> {
      String id = row.getString("device_id");
      return new BrowserDevice(deviceReference(id), deviceCode(id), row.getString("display_name"),
          row.getString("label"), Optional.ofNullable(row.getObject("last_login", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null),
          row.getBoolean("trusted"), Objects.equals(id, currentDeviceId));
    }, username);
  }

  public TrustChange changeTrust(String authorization, String deviceCookie, String username, String reference,
      boolean trusted, AuditWriter audit, String correlation) {
    return tx.execute(status -> {
      jdbc.queryForObject("SELECT username FROM workplace_account WHERE username=? FOR UPDATE", String.class, username);
      var actor = session(authorization).filter(account -> !account.changeRequired()).orElse(null);
      if (actor == null || (actor.role() != Role.HR && actor.role() != Role.ADMIN
          && (!username.equals(actor.username()) || !trustedDevice(authorization, deviceCookie)))) {
        return TrustChange.FORBIDDEN;
      }
      var devices = jdbc.query("SELECT device_id, trusted FROM workplace_browser_device WHERE username=?",
          (row, index) -> new StoredDevice(row.getString(1), row.getBoolean(2)), username);
      var device = devices.stream().filter(candidate -> deviceReference(candidate.id()).equals(reference))
          .findFirst().orElse(null);
      if (device == null) return TrustChange.NOT_FOUND;
      if (device.trusted() == trusted) return TrustChange.OK;
      if (trusted && devices.stream().filter(StoredDevice::trusted).count() >= 3) return TrustChange.LIMIT;
      jdbc.update("UPDATE workplace_browser_device SET trusted=? WHERE username=? AND device_id=?", trusted, username, device.id());
      jdbc.update("UPDATE workplace_account SET device_trust_initialized=TRUE WHERE username=?", username);
      audit.append(actor.username(), trusted ? "DEVICE_TRUST_GRANTED" : "DEVICE_TRUST_REVOKED",
          username + "/" + deviceCode(device.id()), "SUCCESS", correlation, clock.now());
      return TrustChange.OK;
    });
  }

  private static String deviceReference(String id) {
    return UUID.nameUUIDFromBytes(("tmc-browser:" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  public enum TrustChange { OK, FORBIDDEN, NOT_FOUND, LIMIT }

  public record BrowserDevice(String reference, String code, String name, String label, Instant lastLogin,
      boolean trusted, boolean current) {}
  private record StoredDevice(String id, boolean trusted) {}

  private void rememberDevice(String username, String deviceId) {
    jdbc.update("""
        INSERT INTO workplace_browser_device(username,device_id)
        SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM workplace_browser_device WHERE username=? AND device_id=?)
        """, username, deviceId, username, deviceId);
  }

  private static String deviceCode(String deviceId) {
    return deviceId == null ? null : deviceId.substring(0, 8).toUpperCase(Locale.ROOT);
  }

  public Optional<Integer> closeOtherSessions(String authorization) {
    return tokens.verify(authorization, clock.now()).flatMap(token -> tx.execute(status -> {
      jdbc.queryForObject("SELECT username FROM workplace_account WHERE username=? FOR UPDATE", String.class, token.username());
      if (session(authorization).filter(account -> !account.changeRequired()).isEmpty()) return Optional.empty();
      int closed = jdbc.update("DELETE FROM workplace_session WHERE username=? AND session_id<>? AND expires_at>?",
          token.username(), token.id(), utc(clock.now()));
      if (closed > 0) rotateEmployeeCredential(token.username());
      return Optional.of(closed);
    }));
  }

  public boolean changePassword(String username, String current, String replacement) {
    validatePassword(replacement);
    if (!validPasswordLength(current, 1) || replacement.equals(current)) return false;
    return Boolean.TRUE.equals(tx.execute(status -> {
      var hash = jdbc.query("SELECT password_hash FROM workplace_account WHERE username=? AND enabled=TRUE FOR UPDATE",
          rs -> rs.next() ? rs.getString(1) : null, username);
      if (hash == null || !passwords.matches(current, hash)) return false;
      savePassword(username, replacement, false);
      return true;
    }));
  }

  public Optional<Account> account(String username) {
    return jdbc.query(ACCOUNT_COLUMNS + " WHERE a.username=?", ACCOUNT, username).stream().findFirst();
  }

  public List<Account> accounts() { return jdbc.query(ACCOUNT_COLUMNS + " ORDER BY a.username", ACCOUNT); }

  @Override public Optional<Employee> find(String id) {
    return jdbc.query(EMPLOYEES + " WHERE e.employee_id=?", EMPLOYEE, id).stream().findFirst();
  }

  public List<Employee> employees() { return jdbc.query(EMPLOYEES + " ORDER BY e.employee_id", EMPLOYEE); }

  public Optional<String> credentialId(String employeeId) {
    return jdbc.query("SELECT credential_id FROM workplace_employee WHERE employee_id=?",
        rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), employeeId);
  }

  public void bootstrap(String username, String password) {
    if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workplace_account WHERE role='ADMIN')", Boolean.class))) return;
    if (password == null || password.isBlank()) throw new IllegalArgumentException("Cần TMC_ADMIN_PASSWORD để tạo quản trị viên đầu tiên.");
    create(new NewAccount(username, password, Role.ADMIN, null, null, null, null, null, null, null));
  }

  public Account create(NewAccount request) {
    String username = input(request.username(), "tên đăng nhập", 60).toLowerCase(Locale.ROOT);
    if (!username.matches("[a-z0-9][a-z0-9._-]{2,59}")) throw new IllegalArgumentException("Tên đăng nhập dùng 3–60 ký tự a-z, 0-9, dấu chấm, gạch ngang hoặc gạch dưới.");
    validatePassword(request.password());
    if (request.role() == null) throw new IllegalArgumentException("Chọn vai trò.");
    return tx.execute(status -> {
      String employee = null;
      String station = null;
      switch (request.role()) {
        case EMPLOYEE, MANAGER -> employee = provisionEmployee(request);
        case GUARD -> {
          station = input(request.stationId(), "trạm", 100);
          if (!Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM station_registry WHERE station_id=? AND status='ACTIVE')", Boolean.class, station))) {
            throw new IllegalArgumentException("Chọn trạm đang hoạt động.");
          }
        }
        default -> { }
      }
      jdbc.update("INSERT INTO workplace_account(username,password_hash,role,employee_id,station_id) VALUES (?,?,?,?,?)",
          username, passwords.encode(request.password()), request.role().name(), employee, station);
      return account(username).orElseThrow();
    });
  }

  private String provisionEmployee(NewAccount request) {
    var employee = input(request.employeeId(), "mã nhân viên", 60).toUpperCase(Locale.ROOT);
    if (!employee.matches("[A-Z0-9][A-Z0-9_-]*")) throw new IllegalArgumentException("Mã nhân viên chỉ dùng chữ, số, gạch ngang hoặc gạch dưới.");
    var name = input(request.fullName(), "họ tên", 120);
    var site = input(request.siteId(), "nhà máy", 100);
    var department = input(request.departmentId(), "bộ phận", 60).toUpperCase(Locale.ROOT);
    var shift = input(request.shiftId(), "ca", 16);
    if (!List.of("STANDARD", "NIGHT").contains(shift) || request.accessGroup() == null) {
      throw new IllegalArgumentException("Chọn ca và nhóm truy cập hợp lệ.");
    }
    var credential = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO credential(credential_id,employee_id,version,active,device_hash) VALUES (?,?,1,TRUE,?)",
        credential, employee, UUID.randomUUID().toString());
    jdbc.update("INSERT INTO workplace_employee VALUES (?,?,?,?,?,?,?)", employee, name, site, department,
        request.accessGroup().name(), shift, credential);
    return employee;
  }

  public Optional<Account> update(String actor, String username, Boolean enabled, String password) {
    if (actor.equals(username) && (Boolean.FALSE.equals(enabled) || password != null)) {
      throw new IllegalArgumentException("Dùng chức năng đổi mật khẩu cho tài khoản đang đăng nhập.");
    }
    if (password != null) validatePassword(password);
    return tx.execute(status -> {
      if (jdbc.query("SELECT username FROM workplace_account WHERE username=? FOR UPDATE", rs -> !rs.next(), username)) {
        return Optional.empty();
      }
      var existing = account(username).orElseThrow();
      if (enabled != null && enabled != existing.enabled()) {
        jdbc.update("UPDATE workplace_account SET enabled=?,security_version=security_version+1 WHERE username=?", enabled, username);
        jdbc.update("DELETE FROM workplace_session WHERE username=?", username);
        if (existing.employeeId() != null) {
          jdbc.update("UPDATE credential SET active=?,version=version+1 WHERE credential_id=(SELECT credential_id FROM workplace_employee WHERE employee_id=?)",
              enabled, existing.employeeId());
        }
      }
      if (password != null) savePassword(username, password, true);
      return account(username);
    });
  }

  private void savePassword(String username, String password, boolean requireChange) {
    jdbc.update("""
        UPDATE workplace_account SET password_hash=?,change_required=?,security_version=security_version+1,
          failed_attempts=0,locked_until=NULL WHERE username=?
        """, passwords.encode(password), requireChange, username);
    jdbc.update("DELETE FROM workplace_session WHERE username=?", username);
    rotateEmployeeCredential(username);
  }

  private void rotateEmployeeCredential(String username) {
    jdbc.update("""
        UPDATE credential SET version=version+1 WHERE credential_id=(SELECT e.credential_id
          FROM workplace_employee e JOIN workplace_account a ON a.employee_id=e.employee_id WHERE a.username=?)
        """, username);
  }

  private static void validatePassword(String password) {
    if (!validPasswordLength(password, 12)) throw new IllegalArgumentException("Mật khẩu cần ít nhất 12 ký tự và tối đa 72 byte UTF-8.");
  }

  private static boolean validPasswordLength(String password, int minimum) {
    return password != null && password.length() >= minimum && password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }

  private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }

  private static String input(String value, String label, int max) {
    if (value == null || value.isBlank() || value.strip().length() > max) throw new IllegalArgumentException("Kiểm tra trường " + label + ".");
    return value.strip();
  }

  public record Account(String username, String displayName, Role role, String employeeId, String stationId, String siteId,
      boolean enabled, boolean changeRequired) {}
  public record Login(Account account, String token, Duration lifetime) {}
  public record LoginEvent(Instant signedInAt, String deviceLabel, String deviceName, String deviceCode, String clientIp,
      boolean current, boolean active) {}
  public record LoginActivity(int otherSessions, List<LoginEvent> recentLogins, String currentDeviceName) {}
  public record NewAccount(String username, String password, Role role, String employeeId, String fullName,
      String siteId, String departmentId, Group accessGroup, String shiftId, String stationId) {}
  private record LoginRecord(String hash, boolean enabled, int failures, OffsetDateTime lockedUntil, int version, boolean trustInitialized) {}
}
