package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.application.TimesheetSink;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.attendance.infrastructure.XlsxTimesheetSink;

class WorkflowFlowTest {
  @TempDir Path directory;
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private ObjectMapper json;
  private String base;

  @Test
  void policyTimesheetRoleScopesCorrectionsAndPublicationRecoverIndependently() throws Exception {
    try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class, FakeSink.class)
        .run("--server.port=0", "--spring.datasource.url=jdbc:h2:mem:workflow_test", "--spring.profiles.active=workflow-fake-sink",
            "--tmc.workflow.demo=true", "--tmc.timesheet.publish-delay-ms=250",
            "--tmc.timesheet.retry-seconds=1", "--tmc.hr.schedule=-",
            "--tmc.timesheet.export-dir=" + directory)) {
      base = "http://127.0.0.1:" + context.getWebServer().getPort();
      json = context.getBean(ObjectMapper.class);
      var calc = context.getBean(TimesheetCalculator.class);
      var day = calc.workDate(Instant.now());
      var jdbc = context.getBean(JdbcTemplate.class);
      var role = bearer("HR", "");
      var employee = bearer("EMPLOYEE", "NV001");
      var manager = bearer("MANAGER", "");
      var month = YearMonth.from(day).toString();
      var sampleMonth = YearMonth.from(day.minusDays(2)).toString();
      String tables = "/attendance/v1/timesheets";

      assertThat(get(tables + "?month=" + month, null).statusCode()).isEqualTo(401);
      assertThat(get(tables + "?month=" + month + "&employeeId=NV002", employee).statusCode()).isEqualTo(403);
      assertThat(get(tables + "?month=" + month + "&employeeId=NV005", manager).statusCode()).isEqualTo(403);
      assertThat(get(tables + "/employees", manager).body()).contains("NV001", "NV002", "NV003").doesNotContain("NV005");
      assertThat(get(tables + "/employees", role).body()).contains("NV001", "NV006");
      assertThat(get(tables + "/xlsx?month=" + month, manager).statusCode()).isEqualTo(403);
      assertThat(get(tables + "/xlsx?month=" + month, employee).statusCode()).isEqualTo(403);

      var zone = scan("NV001", "GATE-1-PROD", "ENTRY");
      assertThat(zone.path("decision").asText()).isEqualTo("ALLOW");
      assertThat(zone.path("attendanceGate").asBoolean()).isFalse();
      assertThat(scan("NV001", "GATE-1-OFFICE", "ENTRY").path("reasonCode").asText()).isEqualTo("ZONE_DENIED");
      assertThat(scan("NV006", "GATE-1", "ENTRY").path("reasonCode").asText()).isEqualTo("STATION_SCOPE");
      assertThat(scan("NV005", "GATE-1-EXEC", "ENTRY").path("decision").asText()).isEqualTo("ALLOW");
      assertThat(scan("NV005", "GATE-1-PROD", "ENTRY").path("reasonCode").asText()).isEqualTo("ZONE_DENIED");

      var in = scan("NV001", "GATE-1", "ENTRY");
      assertThat(in.path("decision").asText()).isEqualTo("ALLOW");
      var out = scan("NV001", "GATE-1-OUT", "EXIT");
      assertThat(out.path("decision").asText()).isEqualTo("ALLOW");
      String id = out.path("eventId").asText();
      await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE event_id=?", Integer.class, id) == 1);
      await(() -> get(tables + "/export-status?month=" + month, role).body().contains("FAILED"));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE event_id=?", Integer.class,
          zone.path("eventId").asText())).isZero();
      assertThat(get(tables + "?month=" + month, employee).body()).contains("\"status\":\"COMPLETE\"");

      long revision = jdbc.queryForObject("SELECT revision FROM timesheet_publication WHERE period=?", Long.class, month);
      var projections = context.getBean(TimesheetService.class);
      var when = Instant.parse(out.path("scannedAt").asText());
      projections.project("NV001", when);
      projections.project("NV001", when);
      assertThat(jdbc.queryForObject("SELECT revision FROM timesheet_publication WHERE period=?", Long.class, month))
          .isEqualTo(revision);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_timesheet WHERE employee_id=? AND work_date=?",
          Integer.class, "NV001", day)).isEqualTo(1);

      var missingDay = day.minusDays(2);
      var exit = missingDay.atTime(17, 5).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
      var correction = Map.of("employeeId", "NV002", "date", missingDay.toString(),
          "exitAt", exit.toString(), "reason", "Quên quét ra");
      assertThat(post(tables + "/corrections", correction, manager, null).statusCode()).isEqualTo(403);
      assertThat(post(tables + "/corrections", correction, role, null).body()).contains("COMPLETE");
      assertThat(post(tables + "/corrections", correction, role, null).statusCode()).isEqualTo(409);

      context.getBean(AtomicBoolean.class).set(false);
      await(() -> get(tables + "/export-status?month=" + sampleMonth, role).body().contains("SUCCESS"));
      var workbookResponse = HTTP.send(request(tables + "/xlsx?month=" + sampleMonth, role).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertThat(workbookResponse.statusCode()).isEqualTo(200);
      try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbookResponse.body()))) {
        assertThat(book.getNumberOfSheets()).isEqualTo(3);
        assertThat(book.getSheetName(0)).isEqualTo("Tổng hợp");
        assertThat(book.getSheetName(1)).isEqualTo("Chi tiết ngày");
        assertThat(book.getSheetName(2)).isEqualTo("Cần HR xem");
        var names = new HashSet<String>();
        book.getSheetAt(1).forEach(row -> names.add(row.getCell(1).getStringCellValue()));
        assertThat(names).contains("Trần Thị Mai");
        assertThat(book.getSheetAt(1).getRow(0).getCell(6).getStringCellValue()).isEqualTo("Tổng giờ");
      }
      assertThat(get(tables + "/csv?month=" + sampleMonth, role).body())
          .contains("Trần Thị Mai", "9:07").doesNotContain("workflow-sample");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edge_audit WHERE action='TIMESHEET_CORRECT_EXIT'", Integer.class))
          .isEqualTo(1);
    }
  }

  private String bearer(String role, String employee) throws Exception {
    return json.readTree(post("/demo/v1/session", Map.of("role", role, "employeeId", employee), null, null).body())
        .path("bearer").asText();
  }

  private JsonNode scan(String employee, String station, String direction) throws Exception {
    var pass = post("/demo/v1/employees/" + employee + "/qr", Map.of(), null, null);
    assertThat(pass.statusCode()).isEqualTo(200);
    String token = json.readTree(pass.body()).path("token").asText();
    String device = json.readTree(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("did").asText();
    var payload = Map.of("eventId", UUID.randomUUID().toString(), "token", token,
        "siteId", "SITE-1", "direction", direction, "scannedAt", Instant.now().toString(), "deviceId", device);
    return json.readTree(post("/access/v1/verify", payload, null, station).body());
  }

  private HttpRequest.Builder request(String path, String bearer) {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(5));
    return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return HTTP.send(request(path, bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, Object body, String bearer, String station) throws Exception {
    var request = request(path, bearer).header("Content-Type", "application/json");
    if (station != null) request.header("X-Station-Id", station);
    return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void await(CheckedBoolean predicate) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (predicate.get()) return;
      Thread.sleep(100);
    }
    assertThat(predicate.get()).isTrue();
  }

  @FunctionalInterface interface CheckedBoolean { boolean get() throws Exception; }

  @Configuration(proxyBeanMethods = false)
  @Profile("workflow-fake-sink")
  static class FakeSink {
    @Bean AtomicBoolean brokenSink() { return new AtomicBoolean(true); }

    @Bean @Primary TimesheetSink controlledSink(XlsxTimesheetSink xlsx, AtomicBoolean broken) {
      return (rows, period) -> {
        if (broken.get()) throw new IOException("Simulated output failure");
        xlsx.publish(rows, period);
      };
    }
  }
}
