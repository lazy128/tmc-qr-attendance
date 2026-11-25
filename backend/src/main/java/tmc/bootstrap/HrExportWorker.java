package tmc.bootstrap;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.access.application.ScopeResolver;
import tmc.attendance.application.AttendanceCsv;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;

/** Local HR simulation: stores the batch and its simulated receipt in one transaction. */
@Component
public class HrExportWorker {
  private final AttendanceRecordStore records;
  private final ScopeResolver scopes;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final ZoneId zone;

  public HrExportWorker(
      AttendanceRecordStore records, ScopeResolver scopes, JdbcTemplate jdbc,
      PlatformTransactionManager tx, AuditWriter audit, ClockProvider clock,
      @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone
  ) {
    this.records = records;
    this.scopes = scopes;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(tx);
    this.audit = audit;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  @Scheduled(cron = "${tmc.hr.schedule:0 */5 * 1 * *}", zone = "${tmc.hr.zone:Asia/Ho_Chi_Minh}")
  public void sendPreviousMonth() {
    var readyAt = clock.now();
    String month = YearMonth.from(readyAt.atZone(zone)).minusMonths(1).toString();
    transaction.executeWithoutResult(status -> {
      if (Boolean.TRUE.equals(jdbc.queryForObject(
          "SELECT COUNT(*) > 0 FROM hr_export_batch WHERE period=?", Boolean.class, month
      ))) return;
      var allowed = scopes.visibleEmployeeIds("hr", "Authorized");
      var rows = records.all().stream()
          .filter(row -> row.employeeId() != null && allowed.contains(row.employeeId()))
          .toList();
      String csv = AttendanceCsv.render(rows, YearMonth.parse(month), zone);
      var sentAt = clock.now();
      var acknowledgedAt = clock.now();
      jdbc.update(
          "INSERT INTO hr_export_batch(period, csv_payload, ready_at, sent_at, acknowledged_at) VALUES(?,?,?,?,?)",
          month, csv, Timestamp.from(readyAt), Timestamp.from(sentAt), Timestamp.from(acknowledgedAt)
      );
      audit.append("hr-worker", "HR_EXPORT_BATCH", month, "SIMULATED_ACK", month, acknowledgedAt);
    });
  }
}
