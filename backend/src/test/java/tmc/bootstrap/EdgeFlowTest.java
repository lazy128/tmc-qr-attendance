package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.access.application.ScopeResolver;
import tmc.access.infrastructure.turnstile.FakeTurnstileAdapter;
import tmc.access.infrastructure.persistence.JdbcCredentialStore;
import tmc.access.domain.port.Credential;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.bootstrap.worker.HrExportWorker;
import tmc.shared.audit.AuditWriter;
import tmc.shared.audit.AuditHash;
import tmc.shared.web.CorrelationIdFilter;
import tmc.userqr.application.IssueQrToken;

class EdgeFlowTest {
  @TempDir static Path timesheetExports;
  private static final ZoneId HR_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static ServletWebServerApplicationContext app;
  private static ObjectMapper json;
  private static String base;
  private static Map<String, String> deviceProofs;

  @BeforeAll
  static void start() throws Exception {
    app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class).run(
        "--spring.profiles.active=legacy", "--server.port=0", "--spring.datasource.url=jdbc:h2:mem:edge_flow_test",
        "--tmc.timesheet.export-dir=" + timesheetExports,
        "--tmc.hr.schedule=-", "--TMC_HR_DEMO_TOKEN=hr-test-token", "--TMC_GUARD_DEMO_TOKEN=guard-test-token",
        "--TMC_EMPLOYEE_DEMO_TOKEN=employee-test-token", "--TMC_ADMIN_DEMO_TOKEN=admin-test-token",
        "--TMC_EMPLOYEE_2_DEMO_TOKEN=employee-2-test-token",
        "--tmc.cors.origins=http://localhost:5173,http://192.168.20.5:5173"
    );
    base = "http://127.0.0.1:" + app.getWebServer().getPort();
    json = app.getBean(ObjectMapper.class);
    deviceProofs = Map.of("demo-credential", enroll("demo-credential"),
        "demo-credential-2", enroll("demo-credential-2"));
  }

  @AfterAll
  static void stop() {
    if (app != null) app.close();
  }

  @Test
  void configuredLanOriginCanSendDeviceProof() throws Exception {
    var allowed = preflight("http://192.168.20.5:5173");
    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://192.168.20.5:5173");
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
        .containsIgnoringCase("X-Device-Proof");
    assertThat(preflight("http://192.168.20.6:5173").statusCode()).isEqualTo(403);
  }

  private static HttpResponse<String> preflight(String origin) throws Exception {
    return HTTP.send(request("/qr/v1/tokens", null).header("Origin", origin)
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "Authorization, Content-Type, X-Device-Proof")
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void stationRegistryScopesRolesAndBlocksDisabledStations() throws Exception {
    var path = "/admin/v1/stations";
    var admin = "admin-test-token";
    var guard = "guard-test-token";
    var station = "TEST-STATION";
    var create = Map.of("siteId", "SITE-1", "stationId", station, "gateId", "GATE-TEST");
    assertThat(get(path, null).statusCode()).isEqualTo(401);
    assertThat(get(path, "employee-test-token").statusCode()).isEqualTo(403);
    assertThat(post(path, create, guard).statusCode()).isEqualTo(403);
    assertThat(post(path, create, admin).statusCode()).isEqualTo(201);
    assertThat(post(path, create, admin).statusCode()).isEqualTo(409);
    assertThat(get(path, admin).body()).contains(station, "lastHeartbeatAt");
    assertThat(get(path, guard).body()).contains("GATE-1").doesNotContain(station);

    var response = HTTP.send(request(path + "/" + station, admin).header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"DISABLED\"}")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("DISABLED");
    var scan = HTTP.send(request("/access/v1/verify", null).header("Content-Type", "application/json")
        .header("X-Station-Id", station)
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(scan("demo-credential", "demo-device", "ENTRY"))))
        .build(), HttpResponse.BodyHandlers.ofString());
    assertThat(json.readTree(scan.body()).path("reasonCode").asText()).isEqualTo("STATION_AUTH_FAILED");
  }

  @Test
  void requiresIdentityOwnershipAndAnEnrolledDeviceForQrIssue() throws Exception {
    String id = "demo-credential";
    String proof = deviceProofs.get(id);
    assertThat(issue(id, null, proof).statusCode()).isEqualTo(401);
    assertThat(issue("demo-credential-2", "employee-test-token", proof).statusCode()).isEqualTo(403);
    var unknown = issue(id, "employee-test-token", deviceProofs.get("demo-credential-2"));
    assertThat(unknown.statusCode()).isEqualTo(403);
    assertThat(json.readTree(unknown.body()).path("code").asText()).isEqualTo("DEVICE_MISMATCH");
    assertThat(post("/qr/v1/credentials/" + id + "/revoke", Map.of(), null).statusCode()).isEqualTo(401);
    assertThat(post("/qr/v1/credentials/" + id + "/revoke", Map.of(), "employee-test-token").statusCode()).isEqualTo(403);
    assertThat(post("/qr/v1/credentials/" + id + "/devices", Map.of(), "employee-test-token").statusCode()).isEqualTo(403);
    assertThat(issue(id, "employee-test-token", proof).statusCode()).isEqualTo(200);
    assertThat(app.getBean(JdbcTemplate.class).queryForObject(
        "SELECT actor FROM edge_audit WHERE action = 'QR_ISSUE' AND target = ? ORDER BY id DESC LIMIT 1",
        String.class, id)).isEqualTo("employee");

    var revocable = "revocable-credential";
    app.getBean(JdbcCredentialStore.class).save(new Credential(revocable, "demo-employee", 1, true, "demo-device"));
    var revocableProof = enroll(revocable);
    var issued = issue(revocable, "employee-test-token", revocableProof);
    assertThat(issued.statusCode()).isEqualTo(200);
    assertThat(post("/qr/v1/credentials/" + revocable + "/revoke", Map.of(), "hr-test-token").statusCode()).isEqualTo(200);
    assertThat(json.readTree(issue(revocable, "employee-test-token", revocableProof).body()).path("code").asText())
        .isEqualTo("REVOKED");
    assertThat(app.getBean(JdbcTemplate.class).queryForObject(
        "SELECT actor FROM edge_audit WHERE action = 'QR_REVOKE' AND target = ?", String.class, revocable))
        .isEqualTo("hr");
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
  void duplicateWithAnInProgressReservationReturnsRetryableConflict() throws Exception {
    app.getBean(JdbcCredentialStore.class).save(new Credential(
        "pending-credential", "pending-employee", 1, true, "pending-device"
    ));
    var entry = scan("pending-credential", "pending-device", "ENTRY");
    assertThat(verify(entry).path("command").asText()).isEqualTo("ACKED");

    // Keep the stored fingerprint and simulate an in-progress request after the prepared write.
    var jdbc = app.getBean(JdbcTemplate.class);
    assertThat(jdbc.update("UPDATE idempotency_request SET state='IN_PROGRESS' WHERE event_id=?", entry.get("eventId")))
        .isEqualTo(1);
    var conflict = post("/access/v1/verify", entry, null);
    assertThat(conflict.statusCode()).isEqualTo(409);
    var error = json.readTree(conflict.body());
    assertThat(error.path("code").asText()).isEqualTo("REQUEST_IN_PROGRESS");
    assertThat(error.path("retryable").asBoolean()).isTrue();
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
  void centralEndpointAcceptsAndDeduplicatesThePersistedOutboxPayload() throws Exception {
    app.getBean(JdbcCredentialStore.class).save(new Credential(
        "sync-credential", "sync-employee", 1, true, "sync-device"
    ));
    var scan = scan("sync-credential", "sync-device", "ENTRY");
    assertThat(verify(scan).path("command").asText()).isEqualTo("ACKED");
    var jdbc = app.getBean(JdbcTemplate.class);
    String payload = jdbc.queryForObject(
        "SELECT payload FROM edge_outbox WHERE event_id=?", String.class, scan.get("eventId")
    );
    var batch = json.createArrayNode().add(json.readTree(payload));
    var first = post("/edge/v1/sync", batch, null);
    var retry = post("/edge/v1/sync", batch, null);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(retry.statusCode()).isEqualTo(200);
    assertThat(json.readTree(first.body()).get(0).path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(json.readTree(retry.body()).get(0).path("status").asText()).isEqualTo("DUPLICATE");
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

  @Test
  void verifyExposesMetricsAndCorrelatesLogsWithoutRequestData() throws Exception {
    String employee = "employee-private-observability";
    String device = "device-private-observability";
    String secret = "secret-private-observability";
    String correlation = "trace-observability-1";
    app.getBean(JdbcCredentialStore.class).save(new Credential(
        "observability-credential", employee, 1, true, device
    ));

    var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var lines = new ConcurrentLinkedQueue<String>();
    var layout = new PatternLayout();
    layout.setContext(root.getLoggerContext());
    layout.setPattern(app.getEnvironment().getRequiredProperty("logging.pattern.console"));
    layout.start();
    var appender = new AppenderBase<ILoggingEvent>() {
      @Override protected void append(ILoggingEvent event) { lines.add(layout.doLayout(event)); }
    };
    appender.setContext(root.getLoggerContext());
    appender.start();
    root.addAppender(appender);

    String qr;
    try {
      var entry = scan("observability-credential", device, "ENTRY");
      qr = entry.get("token");
      String ackedMetric = "/actuator/metrics/tmc.edge.gate.commands?tag=status:ACKED";
      double previousAcked = measurement(get(ackedMetric, null), "COUNT");
      var outcome = post("/access/v1/verify", entry, secret, correlation);
      assertThat(outcome.statusCode()).isEqualTo(200);
      assertThat(outcome.headers().firstValue(CorrelationIdFilter.HEADER)).contains(correlation);
      assertThat(json.readTree(outcome.body()).path("correlationId").asText()).isEqualTo(correlation);
      assertThat(verify(entry).path("steps").get(0).asText()).isEqualTo("IDEMPOTENT_REPLAY");
      assertThat(measurement(get(ackedMetric, null), "COUNT")).isEqualTo(previousAcked + 1);

      var reused = new java.util.HashMap<>(entry);
      reused.put("eventId", UUID.randomUUID().toString());
      reused.put("scannedAt", Instant.now().toString());
      assertThat(verify(reused).path("reasonCode").asText()).isEqualTo("REPLAY");
      assertThat(measurement(get("/actuator/metrics/tmc.edge.verify.decisions?tag=reasonCode:ALLOW", null), "COUNT"))
          .isGreaterThanOrEqualTo(2);
      assertThat(measurement(get("/actuator/metrics/tmc.edge.verify.decisions?tag=reasonCode:REPLAY", null), "COUNT"))
          .isGreaterThanOrEqualTo(1);
      assertThat(measurement(get(
          "/actuator/metrics/http.server.requests?tag=method:POST&tag=uri:%2Faccess%2Fv1%2Fverify", null
      ), "COUNT")).isGreaterThanOrEqualTo(3);

      var error = post("/access/v1/verify", Map.of(), null, "sensitive@example.test");
      assertThat(error.statusCode()).isEqualTo(400);
      String generated = error.headers().firstValue(CorrelationIdFilter.HEADER).orElseThrow();
      assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
      assertThat(json.readTree(error.body()).path("correlationId").asText()).isEqualTo(generated);
    } finally {
      root.detachAppender(appender);
      appender.stop();
      layout.stop();
    }
    String logs = String.join("", lines);
    assertThat(logs).contains("[" + correlation + "]", "decision=ALLOW reasonCode=ALLOW command=ACKED")
        .doesNotContain(qr, secret, employee, device, "sensitive@example.test", "observability-credential");
  }

  @Test
  void actuatorReportsStaleSnapshotAndRealOutboxDepthWithoutHealthDetails() throws Exception {
    try (var stale = (ServletWebServerApplicationContext) new SpringApplicationBuilder(EdgeApplication.class).run(
        "--spring.profiles.active=legacy", "--server.port=0", "--spring.datasource.url=jdbc:h2:mem:edge_stale_test",
        "--tmc.hr.schedule=-", "--tmc.edge.local-demo-authority=false"
    )) {
      var jdbc = stale.getBean(JdbcTemplate.class);
      jdbc.update("INSERT INTO access_attempt(event_id, command_state) VALUES ('health-pending', 'SENT')");
      jdbc.update("INSERT INTO edge_outbox(event_id) VALUES ('health-pending')");
      var edge = stale.getBean(EdgeHealthIndicator.class).health();
      assertThat(edge.getDetails()).containsEntry("snapshot", "STALE").containsEntry("outboxDepth", 1L);

      var freshResponse = get("/actuator/health", null);
      assertThat(freshResponse.statusCode()).isEqualTo(200);
      assertThat(json.readTree(freshResponse.body())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
      assertThat(measurement(get("/actuator/metrics/tmc.edge.snapshot.fresh", null), "VALUE")).isEqualTo(1);

      String staleBase = "http://127.0.0.1:" + stale.getWebServer().getPort();
      var response = getFrom(staleBase, "/actuator/health");
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"status\":\"OUT_OF_SERVICE\"}"));
      assertThat(measurement(getFrom(staleBase, "/actuator/metrics/tmc.edge.snapshot.fresh"), "VALUE")).isZero();
      assertThat(measurement(getFrom(staleBase, "/actuator/metrics/tmc.edge.outbox.depth"), "VALUE")).isEqualTo(1);
      assertThat(getFrom(staleBase, "/actuator/env").statusCode()).isEqualTo(404);
    }
  }

  private static HttpResponse<String> getFrom(String origin, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5)).GET().build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static double measurement(HttpResponse<String> response, String statistic) throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    for (var metric : json.readTree(response.body()).path("measurements")) {
      if (statistic.equals(metric.path("statistic").asText())) return metric.path("value").asDouble();
    }
    throw new AssertionError("Missing statistic " + statistic);
  }

  private static JsonNode uncheckedVerify(Map<String, String> scan) {
    try {
      return verify(scan);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<String, String> scan(String credential, String device, String direction) throws Exception {
    String token;
    if (deviceProofs.containsKey(credential)) {
      String bearer = credential.equals("demo-credential") ? "employee-test-token" : "employee-2-test-token";
      var issue = issue(credential, bearer, deviceProofs.get(credential));
      assertThat(issue.statusCode()).withFailMessage(issue.body()).isEqualTo(200);
      token = json.readTree(issue.body()).path("token").asText();
    } else {
      token = app.getBean(IssueQrToken.class).issue(credential, device).token();
    }
    return Map.of(
        "eventId", UUID.randomUUID().toString(), "siteId", "SITE-1", "direction", direction,
        "scannedAt", Instant.now().toString(), "deviceId", device,
        "token", token
    );
  }

  private static HttpResponse<String> issue(String credential, String token, String proof) throws Exception {
    var request = request("/qr/v1/tokens", token).header("Content-Type", "application/json");
    if (proof != null) request.header("X-Device-Proof", proof);
    return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
        Map.of("credentialId", credential, "deviceHash", "untrusted-device")))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String enroll(String credential) throws Exception {
    var response = post("/qr/v1/credentials/" + credential + "/devices", Map.of(), "hr-test-token");
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    return json.readTree(response.body()).path("deviceProof").asText();
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
    return post(path, body, token, null);
  }

  private static HttpResponse<String> post(String path, Object body, String token, String correlation) throws Exception {
    var request = request(path, token).header("Content-Type", "application/json").header("X-Station-Id", "GATE-1");
    if (correlation != null) request.header(CorrelationIdFilter.HEADER, correlation);
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
