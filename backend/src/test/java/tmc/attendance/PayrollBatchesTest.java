package tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.TimesheetRow;
import tmc.attendance.domain.TimesheetRow.Status;
import tmc.attendance.infrastructure.JdbcTimesheets;
import tmc.attendance.infrastructure.PayrollBatches;

class PayrollBatchesTest {
  private final YearMonth period = YearMonth.of(2026, 7);
  private final LocalDate date = period.atDay(5);
  private final Instant instant = Instant.parse("2026-08-01T03:00:00Z");
  private final DriverManagerDataSource source = new DriverManagerDataSource(
      "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
  private final JdbcTemplate jdbc = new JdbcTemplate(source);
  private final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  private final JdbcTimesheets sheets = new JdbcTimesheets(jdbc);
  private final AtomicInteger audited = new AtomicInteger();
  private final PayrollBatches batches = new PayrollBatches(jdbc, sheets, new ObjectMapper().findAndRegisterModules(),
      (actor, action, target, result, correlation, time) -> audited.incrementAndGet(),
      new TimesheetCalculator(ZoneOffset.UTC, LocalTime.of(6, 0)));

  PayrollBatchesTest() {
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
  }

  @Test
  void preservesOriginalCsvAndCapturesLateOrCorrectedRowsOnly() {
    sheets.saveIfChanged(row("NV001", 480));
    sheets.saveIfChanged(row("NV002", 120));
    assertThat(batches.history(period).pendingRows()).isEqualTo(2);
    assertThat(capture(instant).number()).isEqualTo(1);
    String original = batches.csv(period, 1).orElseThrow();
    assertThat(original).contains("1,UPSERT,\"NV001\"", "1,UPSERT,\"NV002\"");

    sheets.saveIfChanged(row("NV001", 500));
    sheets.saveIfChanged(row("NV006", 240));
    sheets.saveIfChanged(new TimesheetRow("self-report", "Tự khai", date, "SITE-BD",
        null, null, 0, Status.REVIEW, true));
    assertThat(batches.history(period).pendingRows()).isEqualTo(2);
    assertThat(capture(instant.plusSeconds(30)).number()).isEqualTo(2);
    assertThat(batches.csv(period, 2)).hasValueSatisfying(csv ->
        assertThat(csv).contains("2,UPSERT,\"NV001\"", "2,UPSERT,\"NV006\"").doesNotContain("NV002", "self-report"));
    assertThat(batches.csv(period, 1)).contains(original);
    assertThat(capture(instant.plusSeconds(60)).number()).isEqualTo(2);
    assertThat(batches.history(period).pendingRows()).isZero();
    assertThat(audited).hasValue(2);

    jdbc.update("DELETE FROM daily_timesheet WHERE employee_id='NV001'");
    assertThat(capture(instant.plusSeconds(90)).number()).isEqualTo(3);
    assertThat(batches.csv(period, 3)).hasValueSatisfying(csv -> assertThat(csv)
        .contains("3,VOID,\"NV001\"").doesNotContain("UPSERT"));
  }

  @Test
  void simultaneousExportsReuseTheSameBatch() {
    sheets.saveIfChanged(row("NV001", 480));
    var first = CompletableFuture.supplyAsync(() -> capture(instant));
    var duplicate = CompletableFuture.supplyAsync(() -> capture(instant));
    assertThat(first.join()).isEqualTo(duplicate.join());
    assertThat(batches.history(period).batches()).hasSize(1);
    assertThat(audited).hasValue(1);
  }

  private PayrollBatches.Batch capture(Instant time) {
    return transaction.execute(state -> batches.capture(period, "hr", "test", time).orElseThrow());
  }

  private TimesheetRow row(String id, int minutes) {
    var first = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
    return new TimesheetRow(id, id, date, "SITE-BD", first, first.plusSeconds(60L * minutes),
        minutes, Status.COMPLETE, false);
  }
}
