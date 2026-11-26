package tmc.bootstrap;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tmc.access.infrastructure.security.DemoAccessPolicy;
import tmc.shared.DemoIdentity;
import tmc.shared.StaffDirectory;
import tmc.shared.clock.ClockProvider;
import tmc.shared.clock.SystemClockProvider;
import tmc.shared.id.IdGenerator;
import tmc.shared.id.UuidGenerator;

@Configuration
public class SharedConfiguration {
  @Bean
  DemoAccessPolicy demoAccessPolicy() {
    return new DemoAccessPolicy();
  }

  @Bean
  DemoIdentity demoIdentity(
      @Value("${TMC_HR_DEMO_TOKEN:}") String hr,
      @Value("${TMC_EMPLOYEE_DEMO_TOKEN:}") String employee,
      @Value("${TMC_EMPLOYEE_2_DEMO_TOKEN:}") String employee2,
      @Value("${TMC_MANAGER_DEMO_TOKEN:}") String manager,
      @Value("${TMC_ADMIN_DEMO_TOKEN:}") String admin,
      @Value("${TMC_GUARD_DEMO_TOKEN:}") String guard,
      @Value("${tmc.workflow.demo:false}") boolean demo
  ) {
    var workflow = new HashMap<String, String>();
    if (demo) {
      StaffDirectory.employees().forEach(person -> workflow.put("work-" + person.id(), UUID.randomUUID().toString()));
      workflow.put("work-manager", UUID.randomUUID().toString());
      workflow.put("work-hr", UUID.randomUUID().toString());
    }
    return new DemoIdentity(Map.of(
        "hr", hr, "employee", employee, "employee-2", employee2,
        "manager", manager, "admin", admin, "guard", guard
    ), workflow);
  }

  @Bean
  ClockProvider clockProvider() {
    return new SystemClockProvider();
  }

  @Bean
  IdGenerator idGenerator() {
    return new UuidGenerator();
  }
}
