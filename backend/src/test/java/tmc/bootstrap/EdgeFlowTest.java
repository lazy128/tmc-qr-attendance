package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.access.application.ScopeResolver;
import tmc.access.infrastructure.FakeTurnstileAdapter;
import tmc.access.infrastructure.JdbcCredentialStore;
import tmc.access.domain.port.Credential;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.shared.audit.AuditWriter;
import tmc.shared.audit.AuditHash;

class EdgeFlowTest {
  private static final ZoneId HR_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static ServletWebServerApplicationContext app;
  private static ObjectMapper json;
  private static String base;

  @BeforeAll
  static void start() {
    app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class).run(
        "--server.port=0", "--spring.datasource.url=jdbc:h2:mem:edge_flow_test",
        "--tmc.hr.schedule=-", "--TMC_HR_DEMO_TOKEN=hr-test-token", "--TMC_GUARD_DEMO_TOKEN=guard-test-token",
        "--TMC_EMPLOYEE_DEMO_TOKEN=employee-test-token", "--TMC_ADMIN_DEMO_TOKEN=admin-test-token"
    );
    base = "http://127.0.0.1:" + app.getWebServer().getPort();
    json = app.getBean(ObjectMapper.class);
  }

  @AfterAll
  static void stop() {
    if (app != null) app.close();
  }

  @Test
  void issueScanReplayAttendanceGuardCorrectionAndHrCsv() throws Exception {
    var entry = scan("demo-credential", "demo-device", "ENTRY");
    var verify = verify(entry);
    assertThat(verify.path("decision").asText()).isEqualTo("ALLOW");
    assertThat(verify.path("command").asText()).isEqualTo("ACKED");
    assertThat(verify.path("passage").asText()).isEqualTo("NOT_AVAILABLE");
    assertThat(verify.path("evidence").asText()).isEqualTo("ACK_ONLY");
    assertThat(verify(entry).path("steps").get(0).asText()).isEqualTo("IDEMPOTENT_REPLAY");

    var reused = new java.util.HashMap<>(entry);
    reused.put("eventId", UUID.randomUUID().toString());
    assertThat(verify(reused).path("reasonCode").asText()).isEqualTo("REPLAY");
    assertThat(verify(scan("demo-credential", "demo-device", "ENTRY")).path("reasonCode").asText())
        .isEqualTo("ANTI_PASSBACK");

    var store = app.getBean(AttendanceRecordStore.class);
    String eventId = entry.get("eventId");
    waitFor(() -> store.find(eventId).isPresent());
    assertThat(store.find(eventId).orElseThrow().hrReview()).isTrue();

    assertThat(get("/attendance/v1/records", null).statusCode()).isEqualTo(401);
    assertThat(get("/attendance/v1/records", "admin-test-token").statusCode()).isEqualTo(403);
    assertThat(get("/attendance/v1/records?employeeId=demo-employee-2", "employee-test-token").statusCode())
        .isEqualTo(403);
    assertThat(get("/attendance/v1/records", "employee-test-token").body()).contains(eventId);

    var csvRoute = "/reports/v1/exports?month=" + YearMonth.from(Instant.parse(entry.get("scannedAt")).atZone(HR_ZONE));
    assertThat(get(csvRoute, "employee-test-token").statusCode()).isEqualTo(403);
    var csv = get(csvRoute, "hr-test-token");
    assertThat(csv.statusCode()).isEqualTo(200);
    assertThat(csv.body()).contains("\"" + eventId + "\"", "\"ACK_ONLY\",true").doesNotContain(entry.get("token"));

    var correction = Map.of("lastEntryEventId", eventId, "reason", "MISSED_EXIT");
    assertThat(post("/gates/v1/presence-corrections", correction, "hr-test-token").statusCode()).isEqualTo(403);
    assertThat(post("/gates/v1/presence-corrections", correction, "guard-test-token").statusCode()).isEqualTo(200);
    assertThat(post("/gates/v1/presence-corrections", correction, "guard-test-token").statusCode()).isEqualTo(409);
    assertThat(verify(scan("demo-credential", "demo-device", "ENTRY")).path("command").asText()).isEqualTo("ACKED");
  }

  @Test
  void failedOutboxInsertRollsBackJtiAndDoesNotSendCommand() throws Exception {
    var jdbc = app.getBean(JdbcTemplate.class);
    var turnstile = app.getBean(FakeTurnstileAdapter.class);
    var entry = scan("demo-credential-2", "demo-device-2", "ENTRY");
    String eventId = entry.get("eventId");
    jdbc.execute("ALTER TABLE edge_outbox ADD CONSTRAINT test_fault CHECK (event_id <> '" + eventId + "')");
    try {
      var result = verify(entry);
      assertThat(result.path("reasonCode").asText()).isEqualTo("PERSIST_FAILED");
      assertThat(result.path("command").asText()).isEqualTo("NOT_SENT");
      assertThat(turnstile.status(eventId)).isEqualTo(tmc.access.domain.port.GateResult.TIMEOUT);
    } finally {
      jdbc.execute("ALTER TABLE edge_outbox DROP CONSTRAINT test_fault");
    }
    var retried = new java.util.HashMap<>(entry);
    retried.put("eventId", UUID.randomUUID().toString());
    assertThat(verify(retried).path("command").asText()).isEqualTo("ACKED");
  }

  @Test
  void monthlyHrSimulationPersistsOneAcknowledgedBatchForThePeriod() {
    var first = LocalDate.of(2027, 1, 1).atTime(0, 5).atZone(HR_ZONE);
    var jdbc = app.getBean(JdbcTemplate.class);
    var worker = new HrExportWorker(
        app.getBean(AttendanceRecordStore.class), app.getBean(ScopeResolver.class), jdbc,
        app.getBean(PlatformTransactionManager.class), app.getBean(AuditWriter.class),
        first::toInstant, HR_ZONE.getId()
    );
    worker.sendPreviousMonth();
    worker.sendPreviousMonth();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_export_batch WHERE period='2026-12'", Integer.class))
        .isEqualTo(1);
    var acknowledgedAt = jdbc.queryForObject("""
        SELECT acknowledged_at FROM hr_export_batch WHERE period='2026-12'
        """, java.time.OffsetDateTime.class);
    assertThat(acknowledgedAt.toInstant()).isBefore(first.withHour(12).toInstant());
  }

  @Test
  void concurrentEventsWithOneJtiOnlyCommandTheGateOnce() throws Exception {
    app.getBean(JdbcCredentialStore.class).save(new Credential(
        "concurrency-credential", "concurrency-employee", 1, true, "concurrency-device"
    ));
    var first = scan("concurrency-credential", "concurrency-device", "ENTRY");
    var second = new java.util.HashMap<>(first);
    second.put("eventId", UUID.randomUUID().toString());
    var a = CompletableFuture.supplyAsync(() -> uncheckedVerify(first));
    var b = CompletableFuture.supplyAsync(() -> uncheckedVerify(second));
    assertThat(java.util.List.of(a.get(6, TimeUnit.SECONDS), b.get(6, TimeUnit.SECONDS)))
        .extracting(node -> node.path("command").asText())
        .containsExactlyInAnyOrder("ACKED", "NOT_SENT");
    assertThat(java.util.List.of(a.join(), b.join())).anyMatch(node -> "REPLAY".equals(node.path("reasonCode").asText()));
  }

  @Test
  void auditHashCanBeRecomputedFromStoredFieldsAndDetectsTampering() {
    app.getBean(AuditWriter.class).append(
        "test", "TEST_AUDIT", "opaque-event", "SUCCESS", "audit-test", Instant.now()
    );
    var jdbc = app.getBean(JdbcTemplate.class);
    var hashes = jdbc.queryForObject("""
        SELECT * FROM edge_audit WHERE correlation_id='audit-test'
        """, (rs, row) -> {
      String prior = rs.getString("previous_hash");
      String actor = rs.getString("actor");
      String action = rs.getString("action");
      String result = rs.getString("result");
      String correlation = rs.getString("correlation_id");
      Instant time = rs.getTimestamp("occurred_at").toInstant();
      return java.util.List.of(
          rs.getString("entry_hash"),
          AuditHash.calculate(prior, actor, action, rs.getString("target"), result, correlation, time),
          AuditHash.calculate(prior, actor, action, "changed-target", result, correlation, time)
      );
    });
    assertThat(hashes.get(0)).isEqualTo(hashes.get(1)).isNotEqualTo(hashes.get(2));
  }

  private static JsonNode uncheckedVerify(Map<String, String> scan) {
    try {
      return verify(scan);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<String, String> scan(String credential, String device, String direction) throws Exception {
    var issue = post("/qr/v1/tokens", Map.of("credentialId", credential, "deviceHash", device), null);
    assertThat(issue.statusCode()).isEqualTo(200);
    return Map.of(
        "eventId", UUID.randomUUID().toString(), "siteId", "SITE-1", "direction", direction,
        "scannedAt", Instant.now().toString(), "deviceId", device,
        "token", json.readTree(issue.body()).path("token").asText()
    );
  }

  private static JsonNode verify(Map<String, String> body) throws Exception {
    var response = post("/access/v1/verify", body, null);
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private static HttpResponse<String> get(String path, String token) throws Exception {
    return HTTP.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, Object body, String token) throws Exception {
    var request = request(path, token).header("Content-Type", "application/json").header("X-Station-Id", "GATE-1");
    return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder request(String path, String token) {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    return token == null ? builder : builder.header("Authorization", "Bearer " + token);
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
    assertThat(condition.getAsBoolean()).isTrue();
  }
}
