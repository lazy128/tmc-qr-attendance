package tmc.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tmc.shared.web.CorrelationIdFilter;

@Configuration
public class CorsConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public CorsConfiguration(@Value("${tmc.cors.origins:http://localhost:5173,http://127.0.0.1:5173}") String[] origins) {
    this.origins = origins;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type", "X-Station-Id", CorrelationIdFilter.HEADER, "Idempotency-Key")
        .exposedHeaders(CorrelationIdFilter.HEADER);
  }
}
