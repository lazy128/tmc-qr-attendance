package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Channel;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.PersonalSelfLimitException;
import tmc.access.domain.ReplayDetectedException;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.access.infrastructure.persistence.JdbcPresenceTracker;

@Testcontainers
class PostgresWorkplacePersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final LocalDate DATE = LocalDate.of(2026, 8, 2);
  @TempDir java.nio.file.Path exports;

  @Test
  void appliesSchemaAndPersistsReplayPresenceAndQuotaOnPostgres() {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    try (var app = new SpringApplicationBuilder(EdgeApplication.class)
        .run("--spring.profiles.active=workplace,postgres", "--server.port=0",
            "--TMC_DB_HOST=" + POSTGRES.getHost(), "--TMC_DB_PORT=" + POSTGRES.getMappedPort(5432),
            "--TMC_DB_NAME=" + POSTGRES.getDatabaseName(), "--TMC_DB_USERNAME=" + POSTGRES.getUsername(),
            "--TMC_DB_PASSWORD=" + POSTGRES.getPassword(), "--TMC_ADMIN_PASSWORD=Test-initial-1234",
            "--TMC_SESSION_KEY=" + Base64.getEncoder().encodeToString(secret),
            "--tmc.timesheet.export-dir=" + exports, "--tmc.checkin.personal-self.max-per-day=1",
            "--tmc.access.shift.night-open=22:00")) {
      var jdbc = app.getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(POSTGRES.getDatabaseName());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_registry", Integer.class)).isGreaterThanOrEqualTo(4);
      jdbc.update("INSERT INTO credential(credential_id,employee_id,version,active) VALUES ('cred-pg','NV-PG',1,TRUE)");
      jdbc.update("""
          INSERT INTO workplace_employee(employee_id,full_name,site_id,department_id,access_group,shift_id,credential_id)
          VALUES ('NV-PG','Postgres integration','SITE-BD','LOCAL','STAFF','NIGHT','cred-pg')
          """);
      var attempts = app.getBean(JdbcAttemptStore.class);
      var presence = app.getBean(JdbcPresenceTracker.class);
      var entry = scan("pg-entry", "entry-jti", Channel.GATE_SCAN, Direction.ENTRY, at(DATE, 22, 0));
      assertThat(attempts.reserve(entry.eventId().value(), "pg-entry-fingerprint")).isTrue();
      attempts.persistPrepared(entry, entry.jti());
      assertThat(presence.pendingCommand("SITE-BD", "NV-PG")).isTrue();
      attempts.recordOutcome(entry.withCommand(Command.ACKED));
      assertThat(attempts.findEvent("pg-entry").orElseThrow().result().command()).isEqualTo(Command.ACKED);
      assertThat(presence.pendingCommand("SITE-BD", "NV-PG")).isFalse();
      assertThat(presence.denial("SITE-BD", "NV-PG", Direction.ENTRY, at(DATE, 23, 0)))
          .isEqualTo(Reason.ANTI_PASSBACK);
      assertThatThrownBy(() -> attempts.persistPrepared(
          scan("replay", entry.jti(), Channel.GATE_SCAN, Direction.ENTRY, at(DATE, 23, 0)), entry.jti()))
          .isInstanceOf(ReplayDetectedException.class);
      assertThat(presence.denial("SITE-BD", "NV-PG", Direction.EXIT, at(DATE.plusDays(1), 6, 20))).isNull();
      var exit = scan("pg-exit", "exit-jti", Channel.GATE_SCAN, Direction.EXIT, at(DATE.plusDays(1), 6, 20));
      attempts.persistPrepared(exit, exit.jti());
      attempts.recordOutcome(exit.withCommand(Command.ACKED));
      assertThat(presence.denial("SITE-BD", "NV-PG", Direction.ENTRY, at(DATE.plusDays(1), 22, 0))).isNull();

      var report = scan("report-1", "report-jti", Channel.PERSONAL_SELF, Direction.ENTRY, at(DATE, 23, 0));
      attempts.persistPrepared(report, report.jti());
      var excess = scan("excess", "excess-jti", Channel.PERSONAL_SELF, Direction.EXIT, at(DATE.plusDays(1), 6, 20));
      assertThatThrownBy(() -> attempts.persistPrepared(excess, excess.jti())).isInstanceOf(PersonalSelfLimitException.class);
      assertThat(attempts.alreadyConsumed("SITE-BD", excess.jti())).isFalse();
      var nextDay = scan("report-2", "new-day-jti", Channel.PERSONAL_SELF, Direction.ENTRY, at(DATE.plusDays(1), 22, 0));
      attempts.persistPrepared(nextDay, nextDay.jti());
      assertThat(attempts.findAttempt(nextDay.eventId().value())).get().extracting(AccessAttempt::channel)
          .isEqualTo(Channel.PERSONAL_SELF);
    }
  }

  private static AccessAttempt scan(String id, String jti, Channel channel, Direction direction, Instant at) {
    boolean self = channel == Channel.PERSONAL_SELF;
    String station = direction == Direction.ENTRY ? "BD-G01" : "BD-G01-OUT";
    return new AccessAttempt(new EventId(id), "postgres-test", "NV-PG", new SiteId("SITE-BD"), new StationId(station),
        direction, at, Instant.now(), "cred-pg", 1, jti, Decision.ALLOW, null, self ? Command.NOT_SENT : Command.SENT,
        Passage.NOT_AVAILABLE, false, self ? Evidence.SELF_REPORTED : null, false, true, channel);
  }

  private static Instant at(LocalDate day, int hour, int minute) {
    return day.atTime(hour, minute).atZone(ZONE).toInstant();
  }
}
