package tmc.bootstrap;

import java.time.LocalTime;
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
  TimesheetCalculator timesheetCalculator(EmployeeDirectory employees, @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone,
      @Value("${tmc.timesheet.day-start:06:00}") String dayStart) {
    return new TimesheetCalculator(ZoneId.of(zone), LocalTime.parse(dayStart), employees);
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
