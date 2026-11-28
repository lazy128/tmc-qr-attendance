package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.GateResult;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.access.infrastructure.persistence.JdbcPresenceTracker;
import tmc.access.infrastructure.persistence.JdbcPersonalCheckInQuota;
import tmc.attendance.application.DefaultEligibilityPolicy;
import tmc.attendance.application.TimesheetCalculator;
import tmc.bootstrap.mapping.AccessToAttendance;
import tmc.bootstrap.worker.CommandReconciliationWorker;

class GateIncidentFlowTest {
  private final Instant now = Instant.parse("2026-07-07T02:30:00Z");
  private final DriverManagerDataSource source = new DriverManagerDataSource(
      "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
  private final JdbcTemplate jdbc = new JdbcTemplate(source);
  private final JdbcPresenceTracker presence = new JdbcPresenceTracker(jdbc, true);
  private final JdbcAttemptStore attempts = new JdbcAttemptStore(jdbc, new DataSourceTransactionManager(source),
      Duration.ofMinutes(1), presence,
      new JdbcPersonalCheckInQuota(jdbc, new TimesheetCalculator(ZoneOffset.UTC, LocalTime.MIDNIGHT).calendar(), 4));

  GateIncidentFlowTest() {
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    jdbc.update("INSERT INTO credential(credential_id,employee_id,version,active) VALUES ('cred','NV001',1,TRUE)");
  }

  @Test
  void expiresOnePendingCommandAndAllowsFreshScanWithoutInventingAttendance() {
    var original = prepare("older", now.minusSeconds(121));
    attempts.recordOutcome(original.withCommand(Command.UNKNOWN));
    var enquiries = new AtomicInteger();
    var worker = new CommandReconciliationWorker(attempts, id -> {
      enquiries.incrementAndGet();
      return GateResult.TIMEOUT;
    }, () -> now, 120);
    worker.reconcile();
    worker.reconcile();
    assertThat(enquiries).hasValue(0);
    assertThat(attempts.findAttempt("older").orElseThrow().command()).isEqualTo(Command.EXPIRED);
    assertThat(attempts.findEvent("older").orElseThrow().inProgress()).isFalse();
    assertThat(presence.pendingCommand("SITE-BD", "NV001")).isFalse();
    assertThat(attempts.pendingAttendance(10)).isEmpty();
    assertThat(attempts.gateIncidents("BD-G01")).hasSize(1);
    assertThat(attempts.gateIncidents("LA-G01")).isEmpty();
    assertThat(audited("GATE_COMMAND_EXPIRED")).isEqualTo(1);

    var retry = prepare("newest", now);
    attempts.recordOutcome(retry.withCommand(Command.ACKED));
    attempts.recordOutcome(original.withCommand(Command.ACKED));
    assertThat(attempts.findAttempt("older").orElseThrow().command()).isEqualTo(Command.EXPIRED);

    assertThat(attempts.reviewGateIncident("older", true, "Đã đối chiếu với bảo vệ", "hr", "review-1", now)).isTrue();
    assertThat(attempts.reviewGateIncident("older", false, "Đối soát lại", "hr", "review-2", now)).isFalse();
    assertThat(attempts.gateIncidents(null)).isEmpty();
    assertThat(audited("GATE_INCIDENT_REVIEWED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT event_id FROM employee_presence WHERE employee_id='NV001'", String.class))
        .isEqualTo("newest");
    var old = attempts.findAttempt("older").orElseThrow();
    assertThat(old.evidence()).isEqualTo(Evidence.MANUAL_PASSAGE);
    assertThat(new DefaultEligibilityPolicy().eligible(AccessToAttendance.from(old))).isTrue();
    assertThat(attempts.pendingAttendance(10).stream().map(a -> a.eventId().value()))
        .containsExactlyInAnyOrderElementsOf(List.of("older", "newest"));
  }

  @Test
  void pollsCommandsWithinDeadlineAndReviewOfNoPassageKeepsAttendanceEmpty() {
    var original = prepare("sent", now.minusSeconds(15));
    new CommandReconciliationWorker(attempts, id -> GateResult.TIMEOUT, () -> now, 120).reconcile();
    assertThat(attempts.findAttempt("sent").orElseThrow().command()).isEqualTo(Command.UNKNOWN);
    assertThat(presence.pendingCommand("SITE-BD", "NV001")).isTrue();
    new CommandReconciliationWorker(attempts, id -> GateResult.TIMEOUT, () -> now.plusSeconds(106), 120).reconcile();
    assertThat(attempts.reviewGateIncident("sent", false, "Bảo vệ xác nhận chưa đi qua", "hr", "review-3",
        now.plusSeconds(106))).isTrue();
    assertThat(attempts.findAttempt("sent").orElseThrow().evidence()).isEqualTo(Evidence.MANUAL_NO_PASSAGE);
    assertThat(attempts.pendingAttendance(10)).isEmpty();
    assertThat(presence.pendingCommand("SITE-BD", "NV001")).isFalse();
  }

  private AccessAttempt prepare(String id, Instant time) {
    String jti = "jti-" + id;
    var attempt = new AccessAttempt(new EventId(id), "scan", "NV001", new SiteId("SITE-BD"), new StationId("BD-G01"),
        Direction.ENTRY, time, time, "cred", 1, jti, Decision.ALLOW, null, Command.SENT,
        Passage.NOT_AVAILABLE, false, null, false);
    assertThat(attempts.reserve(id, id)).isTrue();
    attempts.persistPrepared(attempt, jti);
    return attempt;
  }

  private int audited(String action) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM edge_audit WHERE action=?", Integer.class, action);
  }
}
