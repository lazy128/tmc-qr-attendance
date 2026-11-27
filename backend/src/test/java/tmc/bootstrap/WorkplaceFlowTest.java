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

class WorkplaceFlowTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String INITIAL = "Test-initial-1234";
  private static final String PASSWORD = "Test-replaced-5678";
  private static final String ENTRY = "BD-G01";
  @TempDir Path temporary;
  private String base;
  private ObjectMapper json;

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
      assertThat(send("GET", "/auth/v1/me", employee + "x", null).statusCode()).isEqualTo(401);
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
    var active = login(user, PASSWORD);
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
    return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private Map<String, Object> payload(String employee, String site) throws Exception {
    // Adding an employee ID cannot choose the credential. The session chooses the subject.
    var qr = data(send("POST", "/workplace/v1/me/qr", employee, Map.of("employeeId", "NV999")));
    var token = qr.path("token").asText();
    var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
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
