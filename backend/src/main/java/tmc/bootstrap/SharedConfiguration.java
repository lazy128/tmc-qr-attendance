package tmc.bootstrap;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tmc.shared.clock.ClockProvider;
import tmc.shared.clock.SystemClockProvider;
import tmc.shared.id.IdGenerator;
import tmc.shared.id.UuidGenerator;
import tmc.shared.outbox.CentralSyncReceiver;
import tmc.shared.DemoIdentity;
import tmc.access.infrastructure.DemoAccessPolicy;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

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
      @Value("${TMC_MANAGER_DEMO_TOKEN:}") String manager,
      @Value("${TMC_ADMIN_DEMO_TOKEN:}") String admin,
      @Value("${TMC_GUARD_DEMO_TOKEN:}") String guard
  ) {
    return new DemoIdentity(Map.of(
        "hr", hr, "employee", employee, "manager", manager, "admin", admin, "guard", guard
    ));
  }

  @Bean
  ClockProvider clockProvider() {
    return new SystemClockProvider();
  }

  @Bean
  IdGenerator idGenerator() {
    return new UuidGenerator();
  }

  @Bean
  CentralSyncReceiver centralSyncReceiver() {
    return new CentralSyncReceiver();
  }

  @Bean RestTemplate centralHttpClient(RestTemplateBuilder builder) {
    return builder.connectTimeout(Duration.ofMillis(500)).readTimeout(Duration.ofSeconds(2)).build();
  }
}
