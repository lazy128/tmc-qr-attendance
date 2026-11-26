package tmc.bootstrap;

import java.time.LocalTime;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tmc.access.domain.port.Credential;
import tmc.access.infrastructure.persistence.JdbcCredentialStore;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.infrastructure.JdbcAttendanceStore;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.shared.StaffDirectory;
import tmc.shared.clock.ClockProvider;
import tmc.userqr.infrastructure.JdbcDeviceBindings;

@Configuration
@ConditionalOnProperty(name = "tmc.workflow.demo", havingValue = "true")
public class WorkflowDemoConfiguration {
  static String credentialId(String employeeId) {
    return demoId("credential", employeeId);
  }

  private static String demoId(String kind, String employeeId) {
    return UUID.nameUUIDFromBytes(("tmc-workflow/" + kind + "/" + employeeId).getBytes(StandardCharsets.UTF_8)).toString();
  }

  @Bean
  ApplicationRunner workflowSeed(JdbcCredentialStore credentials, JdbcDeviceBindings devices,
      JdbcAttendanceStore events, TimesheetService timesheets, TimesheetCalculator calculator,
      ClockProvider clock, PlatformTransactionManager manager) {
    return args -> new TransactionTemplate(manager).executeWithoutResult(transaction -> {
      for (var employee : StaffDirectory.employees()) {
        String id = credentialId(employee.id());
        credentials.seedIfMissing(new Credential(id, employee.id(), 1, true, demoId("device", employee.id())));
        if (devices.registeredDeviceHash(id).isEmpty()) devices.enroll(credentials.getCredential(id).orElseThrow());
      }
      if (events.find("workflow-sample-0-in").isPresent()) return;
      var latest = calculator.workDate(clock.now()).minusDays(1);
      int index = 0;
      for (var employee : StaffDirectory.employees()) {
        var day = latest.minusDays(index % 3);
        boolean night = employee.shiftId().equals("NIGHT");
        var in = day.atTime(night ? LocalTime.of(22, 0) : LocalTime.of(7, 58))
            .atZone(calculator.zone()).toInstant();
        var out = day.plusDays(night ? 1 : 0).atTime(night ? LocalTime.of(5, 15) : LocalTime.of(17, 5))
            .atZone(calculator.zone()).toInstant();
        String key = "workflow-sample-" + index;
        events.insertIfAbsent(new AttendanceRecord(key + "-in", employee.id(), employee.siteId(), "DEMO-IN",
            "ENTRY", in, "ACKED", "CONFIRMED", "PASSAGE_CONFIRMED", true, false, false));
        if (index != 1) events.insertIfAbsent(new AttendanceRecord(key + "-out", employee.id(),
            employee.siteId(), "DEMO-OUT", "EXIT", out, "ACKED", "CONFIRMED", "PASSAGE_CONFIRMED", true, false, false));
        if (index == 2) events.insertIfAbsent(new AttendanceRecord(key + "-duplicate", employee.id(),
            employee.siteId(), "DEMO-IN", "ENTRY", in.plusSeconds(20), "NOT_SENT", "NOT_AVAILABLE",
            "DUPLICATE_SCAN", false, false, false));
        timesheets.project(employee.id(), in);
        index++;
      }
    });
  }
}
