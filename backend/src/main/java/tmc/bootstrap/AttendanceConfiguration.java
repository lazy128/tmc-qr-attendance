package tmc.bootstrap;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;
import java.time.ZoneId;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.attendance.application.DefaultEligibilityPolicy;
import tmc.attendance.application.RecordAttendance;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.application.TimesheetSink;
import tmc.attendance.infrastructure.JdbcAttendanceStore;
import tmc.attendance.infrastructure.JdbcTimesheets;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.attendance.infrastructure.XlsxTimesheetSink;
import tmc.attendance.infrastructure.GoogleSheetsTimesheetSink;
import tmc.shared.audit.AuditWriter;
import tmc.shared.EmployeeDirectory;
import tmc.shared.ShiftCalendar;
import tmc.shared.ShiftCalendar.Shift;

@Configuration
public class AttendanceConfiguration {
  @Bean
  JdbcAttendanceStore attendanceStore(JdbcTemplate jdbc) {
    return new JdbcAttendanceStore(jdbc);
  }

  @Bean
  RecordAttendance attendance(JdbcAttendanceStore store) {
    return new RecordAttendance(store, new DefaultEligibilityPolicy());
  }

  @Bean
  ShiftCalendar shiftCalendar(EmployeeDirectory employees, @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone,
      @Value("${tmc.timesheet.day-start:06:00}") String dayStart,
      @Value("${tmc.access.shift-grace-minutes:10}") long early,
      @Value("${tmc.access.shift-late-grace-minutes:30}") long late,
      @Value("${tmc.access.shift.standard-open:06:00}") String standardOpen,
      @Value("${tmc.access.shift.standard-close:19:00}") String standardClose,
      @Value("${tmc.access.shift.night-open:20:00}") String nightOpen,
      @Value("${tmc.access.shift.night-close:06:00}") String nightClose) {
    return new ShiftCalendar(ZoneId.of(zone), LocalTime.parse(dayStart), employees, Map.of(
        "STANDARD", new Shift(LocalTime.parse(standardOpen), LocalTime.parse(standardClose)),
        "NIGHT", new Shift(LocalTime.parse(nightOpen), LocalTime.parse(nightClose))),
        Duration.ofMinutes(early), Duration.ofMinutes(late));
  }

  @Bean
  TimesheetCalculator timesheetCalculator(EmployeeDirectory employees, ShiftCalendar calendar) {
    return new TimesheetCalculator(calendar, employees);
  }

  @Bean
  JdbcTimesheets dailyTimesheet(JdbcTemplate jdbc) { return new JdbcTimesheets(jdbc); }

  @Bean
  TimesheetService timesheets(JdbcAttendanceStore events, JdbcTimesheets sheets, TimesheetCalculator calculator,
      AuditWriter audit) {
    return new TimesheetService(events, sheets, calculator, audit);
  }

  @Bean
  XlsxTimesheetSink xlsxTimesheetSink(TimesheetCalculator calculator,
      @Value("${tmc.timesheet.export-dir:exports}") String directory) {
    return new XlsxTimesheetSink(Path.of(directory), calculator.zone());
  }

  @Bean
  @Primary
  @ConditionalOnProperty(name = "tmc.timesheet.google.enabled", havingValue = "true")
  TimesheetSink googleSheetsTimesheetSink(@Value("${tmc.timesheet.google.spreadsheet-id:}") String id,
      @Value("${tmc.timesheet.google.service-account:}") String account) {
    return new GoogleSheetsTimesheetSink(id, Path.of(account));
  }
}
