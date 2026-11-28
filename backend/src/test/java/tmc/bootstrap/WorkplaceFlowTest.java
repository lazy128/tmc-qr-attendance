package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.shared.auth.StationQrTokens;

class WorkplaceFlowTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String INITIAL = "Test-initial-1234";
  private static final String PASSWORD = "Test-replaced-5678";
  private static final String ENTRY = "BD-G01";
  @TempDir Path temporary;
  private String base;
  private ObjectMapper json;

  @Test
  void personalQrIsOwnerBoundAndOnlyCreatesAReportForHr() throws Exception {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    try (var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class)
        .run("--spring.profiles.active=workplace,personal-self-demo", "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:personal_check_in",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--TMC_SESSION_KEY=" + Base64.getEncoder().encodeToString(key), "--TMC_ADMIN_PASSWORD=" + INITIAL)) {
      base = "http://127.0.0.1:" + app.getWebServer().getPort();
      json = app.getBean(ObjectMapper.class);
      var jdbc = app.getBean(JdbcTemplate.class);
      var admin = activate("admin", "/workplace/v1/accounts");
      create(admin, user("owner", "NV211", "Nhân viên có mã", "SITE-BD"));
      create(admin, user("other", "NV212", "Nhân viên khác", "SITE-BD"));
      var desktop = activate("owner", "/workplace/v1/me/employee");
      var phone = cookie(login("owner", PASSWORD));
      var other = activate("other", "/workplace/v1/me/employee");

      var namePath = "/auth/v1/device-name";
      var desktopName = send("PATCH", namePath, desktop, Map.of("name", "MacBook ca sáng"));
      assertThat(desktopName.statusCode()).isEqualTo(204);
      assertThat(cookie(desktopName)).startsWith("tmc_device=");
      assertThat(send("PATCH", namePath, phone, Map.of("name", "iPhone của tôi")).statusCode()).isEqualTo(204);
      assertThat(send("PATCH", namePath, desktop.split(";", 2)[0], Map.of("name", "Cookie thiếu")).statusCode())
          .isEqualTo(401);
      var nextLogin = HTTP.send(request("/auth/v1/login", cookie(desktopName))
          .header("X-TMC-Client-IP", "203.0.113.20").header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", "owner", "password", PASSWORD))))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertThat(nextLogin.statusCode()).isEqualTo(200);
      var activityPath = "/workplace/v1/me/activity";
      var desktopActivity = data(send("GET", activityPath, cookie(nextLogin), null));
      assertThat(desktopActivity.path("currentDeviceName").asText()).isEqualTo("MacBook ca sáng");
      assertThat(desktopActivity.path("recentLogins").get(0).path("clientIp").asText()).isEqualTo("127.0.0.1");
      var desktopCodes = new java.util.ArrayList<String>();
      for (var entry : desktopActivity.path("recentLogins")) {
        if ("MacBook ca sáng".equals(entry.path("deviceName").asText())) desktopCodes.add(entry.path("deviceCode").asText());
      }
      assertThat(desktopCodes).hasSize(3).allMatch(code -> code.matches("[0-9A-F]{8}"));
      assertThat(new java.util.HashSet<>(desktopCodes)).hasSize(1);
      assertThat(data(send("GET", activityPath, phone, null)).path("currentDeviceName").asText()).isEqualTo("iPhone của tôi");
      assertThat(send("GET", activityPath, other, null).body()).doesNotContain("MacBook ca sáng", "iPhone của tôi");

      assertThat(data(send("GET", "/workplace/v1/config", null, null)).path("personalSelfEnabled").asBoolean()).isTrue();
      String personal = "/workplace/v1/me/check-in/personal";
      var issued = send("POST", "/workplace/v1/me/qr", desktop, Map.of("direction", "ENTRY"));
      assertThat(issued.statusCode()).isEqualTo(200);
      String token = data(issued).path("token").asText();
      var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
      assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(60);
      var body = Map.of("code", token, "employeeId", "NV212", "siteId", "SITE-LA");
      assertThat(send("POST", personal, null, body).statusCode()).isEqualTo(401);
      var mismatch = send("POST", personal, other, body);
      assertThat(mismatch.statusCode()).isEqualTo(403);
      assertThat(data(mismatch).path("code").asText()).isEqualTo("QR_OWNER_MISMATCH");
      assertThat(data(send("POST", personal, phone, body)).path("code").asText()).isEqualTo("DEVICE_NOT_TRUSTED");
      approve(desktop, phone, "/workplace/v1/me/devices");

      var recorded = send("POST", personal, phone, body);
      assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(200);
      assertThat(data(recorded).path("command").asText()).isEqualTo("NOT_SENT");
      assertThat(data(recorded).path("evidence").asText()).isEqualTo("SELF_REPORTED");
      assertThat(data(recorded).path("direction").asText()).isEqualTo("ENTRY");
      assertThat(data(send("POST", personal, phone, body))).isEqualTo(data(recorded));
      assertThat(jdbc.queryForObject("SELECT channel FROM access_attempt WHERE employee_id='NV211'", String.class))
          .isEqualTo("PERSONAL_SELF");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee_presence WHERE employee_id='NV211'", Integer.class))
          .isZero();
      for (int attempt = 0; attempt < 80 && reviewReports(jdbc) == 0; attempt++) Thread.sleep(100);
      assertThat(reviewReports(jdbc)).isEqualTo(1);
      assertThat(accepted(jdbc)).isZero();
      assertThat(data(send("GET", "/workplace/v1/me/attendance", desktop, null)).path("status").asText())
          .isEqualTo("NOT_ENTERED");
      var day = app.getBean(tmc.attendance.application.TimesheetCalculator.class).workDateFor("NV211", Instant.now());
      var projected = app.getBean(tmc.attendance.infrastructure.TimesheetService.class).calculate("NV211", day).orElseThrow();
      assertThat(projected.row().workedMinutes()).isZero();
      assertThat(projected.row().reviewFlag()).isTrue();

      for (int report = 0; report < 2; report++) {
        var pass = data(send("POST", "/workplace/v1/me/qr", desktop, Map.of("direction", "EXIT")));
        assertThat(send("POST", personal, phone, Map.of("code", pass.path("token").asText())).statusCode())
            .isEqualTo(200);
      }
      var competing = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
      var codes = new java.util.ArrayList<String>();
      for (int i = 0; i < 2; i++) {
        var pass = data(send("POST", "/workplace/v1/me/qr", desktop, Map.of("direction", "ENTRY")));
        codes.add(pass.path("token").asText());
      }
      for (int i = 0; i < codes.size(); i++) {
        var device = i == 0 ? desktop : phone;
        var payload = json.writeValueAsString(Map.of("code", codes.get(i)));
        var call = request(personal, device).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        competing.add(HTTP.sendAsync(call, HttpResponse.BodyHandlers.ofString()));
      }
      var outcomes = List.of(competing.get(0).join(), competing.get(1).join());
      assertThat(outcomes.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 429);
      int rejectedIndex = outcomes.get(0).statusCode() == 429 ? 0 : 1;
      var rejection = data(outcomes.get(rejectedIndex));
      assertThat(rejection.path("code").asText()).isEqualTo("PERSONAL_SELF_LIMIT");
      assertThat(rejection.path("retryable").asBoolean()).isFalse();
      assertThat(send("POST", personal, phone, Map.of("code", codes.get(rejectedIndex))).statusCode()).isEqualTo(429);
      assertThat(data(send("POST", personal, phone, body))).isEqualTo(data(recorded));
      assertThat(jdbc.queryForObject("""
          SELECT COUNT(*) FROM access_attempt WHERE employee_id='NV211' AND evidence='SELF_REPORTED'
          """, Integer.class)).isEqualTo(4);
      var otherPass = data(send("POST", "/workplace/v1/me/qr", other, Map.of("direction", "ENTRY")));
      assertThat(send("POST", personal, other, Map.of("code", otherPass.path("token").asText())).statusCode())
          .isEqualTo(200);
    }
  }

  private static int reviewReports(JdbcTemplate jdbc) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM attendance_record WHERE evidence='SELF_REPORTED' AND eligible=FALSE AND hr_review=TRUE
        """, Integer.class);
  }

  @Test
  void employeesScanTheSameStationQrUsingTheirOwnSessionsAndRetriesKeepOneAttendanceRecord() throws Exception {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    try (var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class)
        .run("--spring.profiles.active=workplace", "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:station_check_in",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--TMC_SESSION_KEY=" + Base64.getEncoder().encodeToString(key), "--TMC_ADMIN_PASSWORD=" + INITIAL)) {
      base = "http://127.0.0.1:" + app.getWebServer().getPort();
      json = app.getBean(ObjectMapper.class);
      var jdbc = app.getBean(JdbcTemplate.class);
      var admin = activate("admin", "/workplace/v1/accounts");
      create(admin, user("firstbdu", "NV201", "Nhân viên Một", "SITE-BD"));
      create(admin, user("secondbdu", "NV202", "Nhân viên Hai", "SITE-BD"));
      create(admin, user("coworkerla", "NV203", "Nhân viên Long An", "SITE-LA"));
      create(admin, Map.of("username", "guardbdu", "password", INITIAL, "role", "GUARD", "stationId", ENTRY));
      create(admin, Map.of("username", "stationhr", "password", INITIAL, "role", "HR"));
      var firstDesktop = activate("firstbdu", "/workplace/v1/me/employee");
      var firstPhone = cookie(login("firstbdu", PASSWORD));
      var secondPhone = activate("secondbdu", "/workplace/v1/me/employee");
      var longAn = activate("coworkerla", "/workplace/v1/me/employee");
      var guard = activate("guardbdu", "/workplace/v1/station");
      var hr = activate("stationhr", "/attendance/v1/timesheets/employees");
      String checkIn = "/workplace/v1/me/check-in";
      assertThat(data(send("GET", "/workplace/v1/config", null, null)).path("personalSelfEnabled").asBoolean()).isFalse();
      assertThat(send("POST", checkIn + "/personal", firstDesktop, Map.of("code", "unused")).statusCode()).isEqualTo(404);
      assertThat(send("POST", "/workplace/v1/me/qr", firstDesktop, Map.of("direction", "ENTRY")).statusCode()).isEqualTo(404);
      assertThat(data(send("POST", checkIn, firstPhone, Map.of("code", "unused"))).path("code").asText())
          .isEqualTo("DEVICE_NOT_TRUSTED");
      String devices = "/workplace/v1/me/devices";
      String hrDevices = "/workplace/v1/hr/employees/NV201/devices";
      assertThat(send("PATCH", devices + "/" + browserReference(firstPhone), firstPhone, Map.of("trusted", true))
          .statusCode()).isEqualTo(403);
      assertThat(send("GET", hrDevices, secondPhone, null).statusCode()).isEqualTo(403);
      approve(firstDesktop, firstPhone, devices);
      var tablet = cookie(login("firstbdu", PASSWORD));
      var extra = cookie(login("firstbdu", PASSWORD));
      approve(hr, tablet, hrDevices);
      assertThat(data(send("PATCH", hrDevices + "/" + browserReference(extra), hr, Map.of("trusted", true)))
          .path("code").asText()).isEqualTo("DEVICE_LIMIT");
      assertThat(send("PATCH", hrDevices + "/" + browserReference(tablet), hr, Map.of("trusted", false))
          .statusCode()).isEqualTo(204);
      assertThat(data(send("POST", checkIn, tablet, Map.of("code", "unused"))).path("code").asText())
          .isEqualTo("DEVICE_NOT_TRUSTED");
      assertThat(jdbc.queryForObject("""
          SELECT COUNT(*) FROM edge_audit WHERE actor='stationhr' AND action IN ('DEVICE_TRUST_GRANTED','DEVICE_TRUST_REVOKED')
          """, Integer.class)).isEqualTo(2);
      String entryQr = "/workplace/v1/stations/" + ENTRY + "/qr/ENTRY";
      assertThat(send("POST", entryQr, firstPhone, null).statusCode()).isEqualTo(403);
      assertThat(send("POST", "/workplace/v1/stations/LA-G01/qr/ENTRY", guard, null).statusCode()).isEqualTo(403);
      assertThat(send("POST", entryQr, guard, null).statusCode()).isEqualTo(200);
      var old = app.getBean(StationQrTokens.class).issue(ENTRY, "SITE-BD", "ENTRY", Instant.now().minusSeconds(16));
      var expired = send("POST", checkIn, firstPhone, Map.of("code", old.code()));
      assertThat(expired.statusCode()).isEqualTo(409);
      assertThat(data(expired).path("code").asText()).isEqualTo("TOKEN_EXPIRED");

      jdbc.update("""
          INSERT INTO access_attempt(event_id, employee_id, site_id, station_id, scanned_at, received_at,
            direction, decision, command_state, passage_state, channel)
          VALUES ('incident-BD','NV201','SITE-BD','BD-G01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
            'ENTRY','ALLOW','EXPIRED','NOT_AVAILABLE','STATION_QR'),
            ('incident-LA','NV203','SITE-LA','LA-G01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
            'ENTRY','ALLOW','EXPIRED','NOT_AVAILABLE','STATION_QR')
          """);
      String queue = "/gates/v1/incidents";
      assertThat(send("GET", queue, firstDesktop, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", queue, guard, null).body()).contains("incident-BD").doesNotContain("incident-LA");
      assertThat(send("GET", queue, hr, null).body()).contains("incident-BD", "incident-LA");
      assertThat(send("GET", checkIn + "/status/incident-BD", longAn, null).statusCode()).isEqualTo(404);
      assertThat(data(send("GET", checkIn + "/status/incident-BD", firstPhone, null)).path("command").asText())
          .isEqualTo("EXPIRED");
      var review = Map.of("passed", false, "note", "Đối chiếu với bảo vệ tại cổng.");
      assertThat(send("POST", queue + "/incident-BD/review", guard, review).statusCode()).isEqualTo(403);
      assertThat(send("POST", queue + "/incident-BD/review", hr, review).statusCode()).isEqualTo(204);
      assertThat(send("POST", queue + "/incident-BD/review", hr, review).statusCode()).isEqualTo(409);
      assertThat(data(send("GET", checkIn + "/status/incident-BD", firstPhone, null)).path("evidence").asText())
          .isEqualTo("MANUAL_NO_PASSAGE");

      var issued = send("POST", entryQr, admin, null);
      assertThat(issued.statusCode()).isEqualTo(200);
      String code = data(issued).path("code").asText();
      assertThat(send("POST", checkIn, null, Map.of("code", code)).statusCode()).isEqualTo(401);
      assertThat(send("POST", checkIn, longAn, Map.of("code", code)).statusCode()).isEqualTo(403);
      assertThat(send("POST", checkIn, firstPhone, Map.of("code", code + "x")).statusCode()).isEqualTo(400);
      var firstBody = Map.of("code", code, "employeeId", "NV203", "direction", "EXIT");
      var first = send("POST", checkIn, firstPhone, firstBody);
      assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
      assertThat(data(first).path("decision").asText()).isEqualTo("ALLOW");
      assertThat(data(first).path("direction").asText()).isEqualTo("ENTRY");
      assertThat(data(send("POST", checkIn, firstDesktop, firstBody))).isEqualTo(data(first));
      assertThat(data(send("POST", checkIn, secondPhone, Map.of("code", code))).path("decision").asText())
          .isEqualTo("ALLOW");
      for (int attempt = 0; attempt < 80 && accepted(jdbc) != 2; attempt++) Thread.sleep(100);
      assertThat(accepted(jdbc)).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_attempt WHERE channel='STATION_QR' AND command_state='ACKED'",
          Integer.class)).isEqualTo(2);
      var attendance = "/workplace/v1/me/attendance";
      assertThat(data(send("GET", attendance, firstDesktop, null)).path("status").asText()).isEqualTo("IN_SHIFT");
      assertThat(data(send("GET", attendance, longAn, null)).path("status").asText()).isEqualTo("NOT_ENTERED");
      String exit = data(send("POST", "/workplace/v1/stations/" + ENTRY + "-OUT/qr/EXIT", admin, null))
          .path("code").asText();
      var out = data(send("POST", checkIn, firstPhone, Map.of("code", exit)));
      assertThat(out.path("direction").asText()).isEqualTo("EXIT");
      assertThat(out.path("decision").asText()).isEqualTo("ALLOW");
      assertThat(data(send("POST", checkIn, firstDesktop, Map.of("code",
          data(send("POST", entryQr, admin, null)).path("code").asText()))).path("reasonCode").asText())
          .isEqualTo("SHIFT_COMPLETED");

      for (String state : List.of("SENT", "UNKNOWN")) {
        jdbc.update("INSERT INTO access_attempt(event_id,site_id,employee_id,command_state) VALUES (?,?,?,?)",
            "pending-" + state, "SITE-BD", "NV202", state);
        assertThat(data(sendScan(payload(secondPhone, "SITE-BD"), guard, ENTRY)).path("reasonCode").asText())
            .isEqualTo("COMMAND_PENDING");
        String fresh = data(send("POST", entryQr, admin, null)).path("code").asText();
        assertThat(data(send("POST", checkIn, secondPhone, Map.of("code", fresh))).path("reasonCode").asText())
            .isEqualTo("COMMAND_PENDING");
        jdbc.update("DELETE FROM access_attempt WHERE event_id=?", "pending-" + state);
      }

      for (int i = 1; i < 30; i++) {
        assertThat(send("POST", entryQr, guard, null).statusCode()).isEqualTo(200);
      }
      var limited = send("POST", entryQr, guard, null);
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(data(limited).path("code").asText()).isEqualTo("STATION_QR_RATE_LIMIT");
      assertThat(jdbc.queryForObject("""
          SELECT COUNT(*) FROM edge_audit WHERE actor='guardbdu' AND action='STATION_QR_ISSUED' AND target='BD-G01/ENTRY'
          """, Integer.class)).isEqualTo(30);
    }
  }

  @Test
  void enforcesIdentityAndStationScopeAndRecoversConcurrentScansWithoutDuplicateAttendance() throws Exception {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String database = "jdbc:h2:file:" + temporary.resolve("workplace").toAbsolutePath().toString().replace('\\', '/');
    try (var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class)
        .run("--spring.profiles.active=workplace", "--server.port=0", "--spring.datasource.url=" + database,
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--TMC_SESSION_KEY=" + Base64.getEncoder().encodeToString(key), "--TMC_ADMIN_PASSWORD=" + INITIAL,
            "--tmc.timesheet.export-dir=" + temporary.resolve("exports"))) {
      base = "http://127.0.0.1:" + app.getWebServer().getPort();
      json = app.getBean(ObjectMapper.class);
      var jdbc = app.getBean(JdbcTemplate.class);
      assertThat(send("GET", "/demo/v1/employees", null, null).statusCode()).isEqualTo(404);
      assertThat(send("POST", "/workplace/v1/me/qr", null, Map.of()).statusCode()).isEqualTo(401);
      var admin = activate("admin", "/workplace/v1/accounts");
      var stations = data(send("GET", "/admin/v1/stations", admin, null));
      assertThat(stations.path("siteIds").toString()).isEqualTo("[\"SITE-BD\",\"SITE-LA\"]");
      assertThat(stations.path("stations").toString()).contains("BD-G01", "BD-G01-OUT", "LA-G01", "LA-G01-OUT");
      create(admin, user("ngoc", "NV101", "Nguyễn Ngọc", "SITE-BD"));
      create(admin, user("linh", "NV102", "Phạm Linh", "SITE-LA"));
      create(admin, Map.of("username", "congvao", "password", INITIAL, "role", "GUARD", "stationId", ENTRY));
      create(admin, Map.of("username", "congra", "password", INITIAL, "role", "GUARD", "stationId", ENTRY + "-OUT"));
      create(admin, Map.of("username", "baovela", "password", INITIAL, "role", "GUARD", "stationId", "LA-G01"));
      create(admin, Map.of("username", "nhansu", "password", INITIAL, "role", "HR"));
      var employee = activate("ngoc", "/workplace/v1/me/employee");
      var coworker = activate("linh", "/workplace/v1/me/employee");
      var guard = activate("congvao", "/workplace/v1/station");
      var exitGuard = activate("congra", "/workplace/v1/station");
      var longAnGuard = activate("baovela", "/workplace/v1/station");
      var hr = activate("nhansu", "/attendance/v1/timesheets/employees");
      assertThat(data(send("GET", "/workplace/v1/me/employee", employee, null)).path("id").asText()).isEqualTo("NV101");
      assertThat(send("GET", "/auth/v1/me", employee.replace(";", "x;"), null).statusCode()).isEqualTo(401);
      assertThat(send("GET", "/workplace/v1/accounts", employee, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", "/workplace/v1/station", employee, null).statusCode()).isEqualTo(403);
      assertThat(data(send("GET", "/workplace/v1/station", guard, null)).path("stationId").asText()).isEqualTo(ENTRY);
      assertThat(data(send("GET", "/workplace/v1/station", longAnGuard, null)).path("siteId").asText()).isEqualTo("SITE-LA");
      var period = YearMonth.from(app.getBean(tmc.attendance.application.TimesheetCalculator.class).workDate(Instant.now()));
      var month = "/attendance/v1/timesheets?month=" + period;
      assertThat(send("GET", month + "&employeeId=NV102", employee, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", "/attendance/v1/timesheets/csv?month=" + period, employee, null).statusCode()).isEqualTo(403);
      assertThat(send("GET", "/attendance/v1/timesheets/xlsx?month=" + period, employee, null).statusCode()).isEqualTo(403);

      var otherLogin = HTTP.send(request("/auth/v1/login", null)
          .header("Content-Type", "application/json")
          .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", "ngoc", "password", PASSWORD))))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertThat(otherLogin.statusCode()).isEqualTo(200);
      var otherSession = cookie(otherLogin);
      var exposedQr = payload(otherSession, "SITE-BD");
      var activityPath = "/workplace/v1/me/activity";
      var activity = data(send("GET", activityPath, employee, null));
      assertThat(activity.path("otherSessions").asInt()).isEqualTo(1);
      assertThat(activity.path("recentLogins").get(0).path("deviceLabel").asText()).contains("iPhone");
      assertThat(data(send("POST", "/auth/v1/sessions/close-others", employee, null)).path("closed").asInt()).isEqualTo(1);
      assertThat(send("GET", activityPath, otherSession, null).statusCode()).isEqualTo(401);
      assertThat(send("GET", "/auth/v1/me", employee, null).statusCode()).isEqualTo(200);
      assertThat(data(sendScan(exposedQr, guard, ENTRY)).path("reasonCode").asText()).isEqualTo("CVER_MISMATCH");

      var first = payload(employee, "SITE-BD");
      var second = payload(coworker, "SITE-LA");
      var wrongSite = new java.util.HashMap<>(first);
      wrongSite.put("siteId", "SITE-LA");
      wrongSite.put("eventId", UUID.randomUUID().toString());
      assertThat(data(sendScan(wrongSite, longAnGuard, "LA-G01")).path("reasonCode").asText()).isEqualTo("STATION_SCOPE");
      assertThat(sendScan(first, employee, ENTRY).statusCode()).isEqualTo(403);
      assertThat(sendScan(first, exitGuard, ENTRY).statusCode()).isEqualTo(403);
      var calls = List.of(scanAsync(first, guard, ENTRY), scanAsync(second, longAnGuard, "LA-G01"), scanAsync(first, guard, ENTRY));
      CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
      for (var call : calls) assertThat(call.get().statusCode()).isIn(200, 409);
      var retry = data(sendScan(first, guard, ENTRY));
      assertThat(retry.path("decision").asText()).isEqualTo("ALLOW");
      assertThat(retry.path("steps").get(0).asText()).isEqualTo("IDEMPOTENT_REPLAY");
      assertThat(retry.path("employeeName").asText()).isEqualTo("Nguyễn Ngọc");
      assertThat(Instant.parse(retry.path("scannedAt").asText())).isAfter(Instant.parse((String) first.get("scannedAt")));
      var statusPath = "/access/v1/scans/" + first.get("eventId");
      assertThat(HTTP.send(request(statusPath, guard).header("X-Station-Id", ENTRY).GET().build(),
          HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
      assertThat(HTTP.send(request(statusPath, exitGuard).header("X-Station-Id", ENTRY + "-OUT").GET().build(),
          HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);

      var duplicate = new java.util.HashMap<>(first);
      duplicate.put("eventId", UUID.randomUUID().toString());
      var replay = data(sendScan(duplicate, guard, ENTRY));
      assertThat(replay.path("reasonCode").asText()).isEqualTo("REPLAY");
      var independentQr = payload(employee, "SITE-BD");
      assertThat(data(sendScan(independentQr, guard, ENTRY)).path("reasonCode").asText()).isEqualTo("ANTI_PASSBACK");
      for (int attempt = 0; attempt < 80 && accepted(jdbc) != 2; attempt++) Thread.sleep(100);
      assertThat(accepted(jdbc)).isEqualTo(2);
      assertThat(data(send("GET", "/workplace/v1/me/attendance", employee, null)).path("status").asText()).isEqualTo("IN_SHIFT");
      var updatedActivity = data(send("GET", activityPath, employee, null));
      assertThat(updatedActivity.path("otherSessions").asInt()).isZero();
      assertThat(updatedActivity.path("recentLogins").get(0).path("active").asBoolean()).isFalse();
      assertThat(updatedActivity.path("scans").size()).isEqualTo(1);
      assertThat(updatedActivity.path("scans").get(0).path("eventId").asText()).isEqualTo(first.get("eventId"));
      assertThat(updatedActivity.path("scans").get(0).path("siteId").asText()).isEqualTo("SITE-BD");
      assertThat(send("GET", month, employee, null).body()).contains("Nguyễn Ngọc").doesNotContain("Phạm Linh");
      assertThat(send("GET", month, hr, null).body()).contains("Nguyễn Ngọc", "Phạm Linh");
      assertThat(send("GET", month + "&siteId=SITE-LA", admin, null).body()).contains("Phạm Linh").doesNotContain("Nguyễn Ngọc");
      assertThat(send("GET", "/attendance/v1/timesheets/employees?siteId=SITE-BD", admin, null).body())
          .contains("NV101").doesNotContain("NV102");
      var query = "?month=" + period + "&siteId=SITE-LA";
      assertThat(send("GET", "/attendance/v1/timesheets/csv" + query, admin, null).body())
          .contains("Phạm Linh", "Long An").doesNotContain("Nguyễn Ngọc");
      var excel = HTTP.send(request("/attendance/v1/timesheets/xlsx" + query, admin).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertThat(excel.statusCode()).isEqualTo(200);
      try (var book = new XSSFWorkbook(new ByteArrayInputStream(excel.body()))) {
        var detail = book.getSheet("Chi tiết ngày");
        assertThat(detail.getLastRowNum()).isEqualTo(1);
        assertThat(detail.getRow(1).getCell(0).getStringCellValue()).isEqualTo("NV102");
        assertThat(detail.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Long An");
      }
      assertThat(send("PATCH", "/workplace/v1/accounts/ngoc", admin, Map.of("enabled", false)).statusCode()).isEqualTo(200);
      assertThat(send("GET", "/auth/v1/me", employee, null).statusCode()).isEqualTo(401);
      assertThat(send("POST", "/auth/v1/logout", guard, null).statusCode()).isEqualTo(204);
      assertThat(sendScan(first, guard, ENTRY).statusCode()).isEqualTo(401);
    }
    try (var db = DriverManager.getConnection(database, "sa", ""); var statement = db.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM attendance_record WHERE eligible=TRUE")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(2);
    }
  }

  private static int accepted(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE eligible=TRUE", Integer.class);
  }

  private Map<String, Object> user(String username, String employeeId, String name, String site) {
    return Map.of("username", username, "password", INITIAL, "role", "EMPLOYEE", "employeeId", employeeId,
        "fullName", name, "siteId", site, "departmentId", "SAN-XUAT", "accessGroup", "STAFF", "shiftId", "STANDARD");
  }

  private void create(String admin, Map<String, Object> request) throws Exception {
    var response = send("POST", "/workplace/v1/accounts", admin, request);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  private String activate(String user, String protectedUrl) throws Exception {
    var initial = login(user, INITIAL);
    assertThat(data(initial).path("changeRequired").asBoolean()).isTrue();
    assertThat(send("GET", protectedUrl, cookie(initial), null).statusCode()).isIn(401, 403);
    assertThat(send("POST", "/auth/v1/password", cookie(initial), Map.of("current", INITIAL, "replacement", PASSWORD))
        .statusCode()).isEqualTo(204);
    assertThat(send("GET", "/auth/v1/me", cookie(initial), null).statusCode()).isEqualTo(401);
    var active = send("POST", "/auth/v1/login", deviceCookie(cookie(initial)), Map.of("username", user, "password", PASSWORD));
    assertThat(active.statusCode()).as(active.body()).isEqualTo(200);
    assertThat(data(active).path("changeRequired").asBoolean()).isFalse();
    return cookie(active);
  }

  private HttpResponse<String> login(String user, String password) throws Exception {
    var reply = send("POST", "/auth/v1/login", null, Map.of("username", user, "password", password));
    assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
    assertThat(reply.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "Secure", "SameSite=Strict");
    return reply;
  }

  private String cookie(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream().map(value -> value.split(";", 2)[0])
        .collect(java.util.stream.Collectors.joining("; "));
  }

  private String deviceCookie(String cookies) {
    return java.util.Arrays.stream(cookies.split("; ")).filter(value -> value.startsWith("tmc_device="))
        .findFirst().orElseThrow();
  }

  private String browserReference(String session) throws Exception {
    var devices = data(send("GET", "/workplace/v1/me/devices", session, null)).path("devices");
    for (var device : devices) {
      if (device.path("current").asBoolean()) return device.path("reference").asText();
    }
    throw new AssertionError("Current browser missing");
  }

  private void approve(String actor, String browser, String devices) throws Exception {
    var reply = send("PATCH", devices + "/" + browserReference(browser), actor, Map.of("trusted", true));
    assertThat(reply.statusCode()).as(reply.body()).isEqualTo(204);
  }

  private Map<String, Object> payload(String employee, String site) throws Exception {
    // Adding an employee ID cannot choose the credential. The session chooses the subject.
    var qr = data(send("POST", "/workplace/v1/me/qr", employee, Map.of("employeeId", "NV999")));
    var token = qr.path("token").asText();
    var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(30);
    return Map.of("eventId", UUID.randomUUID().toString(), "siteId", site, "direction", "ENTRY",
        "scannedAt", Instant.now().minusSeconds(12).toString(), "token", token, "deviceId", claims.path("did").asText());
  }

  private HttpResponse<String> sendScan(Map<String, Object> payload, String guard, String station) throws Exception {
    return HTTP.send(scanRequest(payload, guard, station), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> scanAsync(Map<String, Object> payload, String guard, String station) throws Exception {
    return HTTP.sendAsync(scanRequest(payload, guard, station), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest scanRequest(Map<String, Object> payload, String guard, String station) throws Exception {
    return request("/access/v1/verify", guard).header("X-Station-Id", station)
        .header("Idempotency-Key", (String) payload.get("eventId")).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
  }

  private HttpResponse<String> send(String method, String path, String session, Map<String, ?> payload) throws Exception {
    var request = request(path, session);
    var body = payload == null ? HttpRequest.BodyPublishers.noBody() :
        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
    if (payload != null) request.header("Content-Type", "application/json");
    return HTTP.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path, String session) {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    return session == null ? request : request.header("Cookie", session);
  }

  private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
}
