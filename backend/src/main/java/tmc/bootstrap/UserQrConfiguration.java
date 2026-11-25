package tmc.bootstrap;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tmc.access.infrastructure.security.Es256TokenCodec;
import tmc.access.infrastructure.persistence.JdbcCredentialStore;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.shared.clock.ClockProvider;
import tmc.shared.id.IdGenerator;
import tmc.userqr.application.IssueQrToken;
import tmc.userqr.application.RevokeCredential;

@Configuration
public class UserQrConfiguration {
  @Bean
  IssueQrToken issueQrToken(
      JdbcCredentialStore credentials,
      Es256TokenCodec codec,
      ClockProvider clock,
      IdGenerator ids,
      @Value("${tmc.qr.ttl-seconds:30}") long ttl,
      @Value("${tmc.qr.signing-kid:demo}") String kid
  ) {
    return new IssueQrToken(credentials, codec, clock, ids, Duration.ofSeconds(ttl), "tmc", "tmc-gates", kid);
  }

  @Bean
  RevokeCredential revokeCredential(
      JdbcCredentialStore credentials,
      JdbcAttemptStore audit,
      ClockProvider clock
  ) {
    return new RevokeCredential(credentials, credentials, audit, clock);
  }
}
