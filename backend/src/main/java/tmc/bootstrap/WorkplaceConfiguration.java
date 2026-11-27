package tmc.bootstrap;

import java.time.Duration;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import tmc.access.infrastructure.security.WorkplacePolicy;
import tmc.shared.auth.StationQrTokens;
import tmc.shared.auth.WorkplaceAccounts;
import tmc.shared.clock.ClockProvider;

@Configuration
@ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "true")
public class WorkplaceConfiguration {
  static final String SESSION_COOKIE = "tmc_session";

  @Bean
  WorkplaceAccounts workplaceAccounts(JdbcTemplate jdbc, PlatformTransactionManager manager, ClockProvider clock,
      @Value("${TMC_SESSION_KEY:}") String key, @Value("${tmc.accounts.session-minutes:480}") long minutes) {
    return new WorkplaceAccounts(jdbc, new TransactionTemplate(manager), clock, key, Duration.ofMinutes(minutes));
  }

  @Bean
  WorkplacePolicy workplacePolicy(WorkplaceAccounts accounts) { return new WorkplacePolicy(accounts); }

  @Bean
  StationQrTokens stationQrTokens(@Value("${TMC_SESSION_KEY:}") String key) { return new StationQrTokens(key); }

  @Bean
  ApplicationRunner firstAdministrator(WorkplaceAccounts accounts,
      @Value("${TMC_ADMIN_USERNAME:admin}") String username, @Value("${TMC_ADMIN_PASSWORD:}") String password) {
    return args -> accounts.bootstrap(username, password);
  }

  @Bean
  FilterRegistrationBean<jakarta.servlet.Filter> cookieAuthorization() {
    FilterRegistrationBean<jakarta.servlet.Filter> filter = new FilterRegistrationBean<>((request, response, chain) -> {
      var http = (HttpServletRequest) request;
      var cookies = http.getCookies();
      if (http.getHeader(HttpHeaders.AUTHORIZATION) == null && cookies != null) {
        for (var cookie : cookies) {
          if (!SESSION_COOKIE.equals(cookie.getName())) continue;
          String header = "Bearer " + cookie.getValue();
          chain.doFilter(new HttpServletRequestWrapper(http) {
            @Override public String getHeader(String name) {
              return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? header : super.getHeader(name);
            }
            @Override public java.util.Enumeration<String> getHeaders(String name) {
              return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                  ? Collections.enumeration(java.util.List.of(header)) : super.getHeaders(name);
            }
          }, response);
          return;
        }
      }
      chain.doFilter(request, response);
    });
    filter.setName("workplaceCookieAuthorization");
    return filter;
  }
}
