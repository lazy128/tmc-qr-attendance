package tmc.bootstrap;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.sync.infrastructure.JdbcCentralEventStore;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestTemplate;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Evidence;
import tmc.attendance.application.AttendanceRecorder;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.bootstrap.mapping.AccessToAttendance;
import tmc.sync.application.CentralAttendanceConsumer;
import tmc.sync.application.CentralSyncReceiver;

@Configuration
public class SyncConfiguration {
  @Bean
  CentralSyncReceiver centralSyncReceiver(JdbcTemplate jdbc, PlatformTransactionManager tx, ObjectMapper json) {
    return new CentralSyncReceiver(new JdbcCentralEventStore(jdbc, tx, json));
  }

  @Bean
  RestTemplate centralHttpClient(RestTemplateBuilder builder) {
    return builder.connectTimeout(Duration.ofMillis(500)).readTimeout(Duration.ofSeconds(2)).build();
  }

  @Bean
  @ConditionalOnProperty(name = "tmc.central.attendance", havingValue = "true")
  CentralAttendanceConsumer centralAttendance(AttendanceRecorder recorder, TimesheetService timesheets) {
    return attempt -> {
      if (!attempt.attendanceGate() || attempt.employeeId() == null
          || (attempt.command() != Command.ACKED && !attempt.duplicateScan() && attempt.evidence() != Evidence.SELF_REPORTED && attempt.evidence() != Evidence.MANUAL_PASSAGE)) return;
      recorder.accept(AccessToAttendance.from(attempt));
      timesheets.project(attempt.employeeId(), attempt.scannedAt());
    };
  }
}
