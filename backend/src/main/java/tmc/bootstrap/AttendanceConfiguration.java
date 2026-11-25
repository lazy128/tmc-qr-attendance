package tmc.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.attendance.application.DefaultEligibilityPolicy;
import tmc.attendance.application.RecordAttendance;
import tmc.attendance.infrastructure.JdbcAttendanceStore;

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
}
