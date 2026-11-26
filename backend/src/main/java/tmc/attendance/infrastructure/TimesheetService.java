package tmc.attendance.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.TimesheetRow;
import tmc.shared.audit.AuditWriter;

public class TimesheetService {
  private final JdbcAttendanceStore events;
  private final JdbcTimesheets sheets;
  private final TimesheetCalculator calculator;
  private final AuditWriter audit;

  public TimesheetService(JdbcAttendanceStore events, JdbcTimesheets sheets, TimesheetCalculator calculator,
      AuditWriter audit) {
    this.events = events;
    this.sheets = sheets;
    this.calculator = calculator;
    this.audit = audit;
  }

  @Transactional
  public void project(String id, Instant scannedAt) {
    if (id == null) return;
    var day = calculator.workDate(scannedAt);
    sheets.lock(id, day);
    calculate(id, day).ifPresent(result -> sheets.saveIfChanged(result.row()));
  }

  @Transactional
  public TimesheetRow correctExit(String id, LocalDate date, Instant exit, String reason,
      String actor, String correlation, Instant now) {
    if (exit == null || exit.isAfter(now) || reason == null || reason.isBlank() || reason.length() > 300) {
      throw new IllegalArgumentException("Điền giờ ra đã qua và lý do tối đa 300 ký tự.");
    }
    sheets.lock(id, date);
    var result = calculate(id, date).orElseThrow(() -> new IllegalStateException("Không có bản công cho ngày này."));
    var open = result.unpairedEntry();
    if (open == null || sheets.adjustment(id, date) != null) throw new IllegalStateException("Ca này không còn thiếu giờ ra.");
    if (!exit.isAfter(open) || !exit.isBefore(calculator.start(date.plusDays(1)))) {
      throw new IllegalArgumentException("Giờ ra phải sau giờ vào và trong cùng ngày công.");
    }
    sheets.addAdjustment(id, date, exit, reason.strip(), actor);
    var updated = calculate(id, date).orElseThrow().row();
    sheets.saveIfChanged(updated);
    audit.append(actor, "TIMESHEET_CORRECT_EXIT", id + "/" + date, "SUCCESS", correlation, now);
    return updated;
  }

  public List<TimesheetRow> month(YearMonth month) { return sheets.month(month); }
  public List<AttendanceRecord> scans(String id, LocalDate date) {
    return events.forEmployeeBetween(id, calculator.start(date), calculator.start(date.plusDays(1)));
  }

  public Optional<TimesheetCalculator.Calculation> calculate(String id, LocalDate date) {
    return calculator.calculate(id, date, scans(id, date), sheets.adjustment(id, date));
  }
}
