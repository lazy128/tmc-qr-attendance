package tmc.bootstrap;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import tmc.sync.application.CentralSyncReceiver;

@Configuration
public class SyncConfiguration {
  @Bean
  CentralSyncReceiver centralSyncReceiver() {
    return new CentralSyncReceiver();
  }

  @Bean
  RestTemplate centralHttpClient(RestTemplateBuilder builder) {
    return builder.connectTimeout(Duration.ofMillis(500)).readTimeout(Duration.ofSeconds(2)).build();
  }
}
