package tmc.bootstrap.worker;

import java.io.IOException;
import java.time.YearMonth;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.application.ScopeResolver;
import tmc.attendance.application.TimesheetSink;
import tmc.attendance.infrastructure.JdbcAttendanceStore;
import tmc.attendance.infrastructure.JdbcTimesheets;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.shared.clock.ClockProvider;

@Component
public class TimesheetPublicationWorker {
  private static final Logger log = LoggerFactory.getLogger(TimesheetPublicationWorker.class);
  private final JdbcAttendanceStore events;
  private final JdbcTimesheets sheets;
  private final TimesheetService timesheets;
  private final TimesheetSink sink;
  private final ClockProvider clock;
  private final Set<String> employees;
  private final boolean workplace;
  private final java.time.ZoneId zone;
  private final long retrySeconds;
  private boolean initialized;

  public TimesheetPublicationWorker(JdbcAttendanceStore events, JdbcTimesheets sheets, TimesheetService timesheets,
      TimesheetSink sink, ClockProvider clock, ScopeResolver scopes,
      @Value("${tmc.workflow.demo:false}") boolean demo,
      @Value("${tmc.accounts.enabled:false}") boolean workplace,
      @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone,
      @Value("${tmc.timesheet.retry-seconds:30}") long retrySeconds) {
    this.events = events;
    this.sheets = sheets;
    this.timesheets = timesheets;
    this.sink = sink;
    this.clock = clock;
    this.workplace = workplace;
    this.employees = workplace ? Set.of() : scopes.visibleEmployeeIds(demo ? "work-hr" : "hr", "Timesheet");
    this.zone = java.time.ZoneId.of(zone);
    this.retrySeconds = retrySeconds;
  }

  @Scheduled(fixedDelayString = "${tmc.timesheet.publish-delay-ms:5000}")
  public void dispatch() {
    if (!initialized) {
      for (var event : events.all()) timesheets.project(event.employeeId(), event.scannedAt());
      initialized = true;
    }
    for (var item : sheets.due(clock.now())) {
      if ("FAILED".equals(item.status())) sheets.publicationResult(item, "RETRYING", null, null);
      try {
        var period = YearMonth.parse(item.period());
        var rows = sheets.month(period).stream().filter(row -> workplace || employees.contains(row.employeeId())).toList();
        sink.publish(rows, period);
        sheets.publicationResult(item, "SUCCESS", clock.now(), null);
      } catch (IOException | RuntimeException failure) {
        log.warn("Timesheet export failed for period {} ({})", item.period(), failure.getClass().getSimpleName());
        sheets.publicationResult(item, "FAILED", null, clock.now().plusSeconds(retrySeconds));
      }
    }
  }

  @Scheduled(cron = "${tmc.timesheet.close-cron:0 30 11 1 * *}", zone = "${tmc.hr.zone:Asia/Ho_Chi_Minh}")
  public void closePreviousMonth() {
    sheets.markPending(YearMonth.from(clock.now().atZone(zone)).minusMonths(1));
  }

}
