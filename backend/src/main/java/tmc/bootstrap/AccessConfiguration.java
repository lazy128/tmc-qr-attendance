package tmc.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.access.application.RecordPassageEvidence;
import tmc.access.application.VerificationPipeline;
import tmc.access.application.VerifyAccessUseCase;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.GateResult;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.domain.port.Claims;
import tmc.access.domain.AccessRequest;
import tmc.access.infrastructure.EdgeSecuritySimulation;
import tmc.access.infrastructure.Es256TokenCodec;
import tmc.access.infrastructure.FakeTurnstileAdapter;
import tmc.access.infrastructure.FileSnapshotProvider;
import tmc.access.infrastructure.JdbcCredentialStore;
import tmc.access.infrastructure.JdbcPresenceTracker;
import tmc.access.infrastructure.JdbcAttemptStore;
import tmc.access.infrastructure.MutableSnapshot;
import tmc.access.infrastructure.TimeoutGateCommandSender;
import tmc.shared.clock.ClockProvider;

@Configuration
public class AccessConfiguration {
  @Bean
  JdbcCredentialStore credentialStore(JdbcTemplate jdbc) {
    var store = new JdbcCredentialStore(jdbc);
    store.seedIfMissing(new Credential("demo-credential", "demo-employee", 1, true, "demo-device"));
    store.seedIfMissing(new Credential("demo-credential-2", "demo-employee-2", 1, true, "demo-device-2"));
    return store;
  }

  @Bean
  JdbcPresenceTracker presenceTracker(JdbcTemplate jdbc) {
    return new JdbcPresenceTracker(jdbc);
  }

  @Bean
  SnapshotProvider snapshot(
      ClockProvider clock, ObjectMapper json,
      @Value("${tmc.edge.snapshot-file:}") String snapshotFile,
      @Value("${tmc.edge.local-demo-authority:true}") boolean localAuthority
  ) {
    if (!snapshotFile.isBlank()) return new FileSnapshotProvider(Path.of(snapshotFile), json);
    if (!localAuthority) return new MutableSnapshot(Instant.EPOCH);
    // The isolated demo reads authoritative credentials and revocations directly from local H2.
    return new SnapshotProvider() {
      @Override public Instant snapshotTime() { return clock.now(); }
      @Override public boolean revoked(String credentialId) { return false; }
      @Override public boolean policyAllows(Claims claims, AccessRequest request) { return true; }
    };
  }

  @Bean
  Es256TokenCodec tokenCodec(
      @Value("${tmc.qr.keystore:}") String keyStorePath,
      @Value("${TMC_QR_KEYSTORE_PASSWORD:}") String keyStorePassword,
      @Value("${tmc.qr.signing-kid:demo}") String kid
  ) throws Exception {
    if (!keyStorePath.isBlank()) {
      var keyStore = KeyStore.getInstance("PKCS12");
      char[] password = keyStorePassword.toCharArray();
      try (InputStream file = Files.newInputStream(Path.of(keyStorePath))) {
        keyStore.load(file, password);
      }
      var publicKeys = new HashMap<String, ECPublicKey>();
      for (String alias : java.util.Collections.list(keyStore.aliases())) {
        var certificate = keyStore.getCertificate(alias);
        if (certificate != null && certificate.getPublicKey() instanceof ECPublicKey key
            && key.getParams().getCurve().getField().getFieldSize() == 256) {
          publicKeys.put(alias, key);
        }
      }
      if (!publicKeys.containsKey(kid) || !(keyStore.getKey(kid, password) instanceof ECPrivateKey signer)) {
        throw new IllegalArgumentException("The configured signing kid must reference an EC key pair");
      }
      return new Es256TokenCodec(publicKeys, Map.of(kid, signer));
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    return new Es256TokenCodec(
        Map.of(kid, (ECPublicKey) pair.getPublic()),
        Map.of(kid, (ECPrivateKey) pair.getPrivate())
    );
  }

  @Bean
  FakeTurnstileAdapter turnstile() {
    return new FakeTurnstileAdapter(GateResult.ACKED);
  }

  @Bean(destroyMethod = "close")
  TimeoutGateCommandSender boundedTurnstile(
      FakeTurnstileAdapter fake,
      @Value("${tmc.turnstile.timeout-millis:800}") long timeout
  ) {
    return new TimeoutGateCommandSender(fake, timeout);
  }

  @Bean
  JdbcAttemptStore jdbcAttemptStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager tx,
      JdbcPresenceTracker presence,
      @Value("${tmc.idempotency.reservation-ttl-seconds:60}") long reservationTtlSeconds
  ) {
    return new JdbcAttemptStore(jdbc, tx, Duration.ofSeconds(reservationTtlSeconds), presence);
  }

  @Bean
  RecordPassageEvidence recordPassageEvidence(JdbcAttemptStore attempts, JdbcPresenceTracker presence) {
    return new RecordPassageEvidence(attempts, presence);
  }

  @Bean
  VerifyAccessUseCase verifyAccess(
      JdbcCredentialStore credentials,
      JdbcAttemptStore jdbc,
      SnapshotProvider snapshot,
      Es256TokenCodec codec,
      TimeoutGateCommandSender gate,
      JdbcPresenceTracker presence,
      ClockProvider clock,
      @Value("${tmc.edge.station:GATE-1}") String station,
      @Value("${tmc.edge.site:SITE-1}") String site,
      @Value("${tmc.edge.passage-sensor:false}") boolean sensorConfigured,
      @Value("${tmc.clock.skew-seconds:5}") long skew,
      @Value("${tmc.edge.snapshot-max-age-seconds:900}") long snapshotAge
  ) {
    var security = new EdgeSecuritySimulation(station, site);
    var steps = VerificationPipeline.create(
        security,
        security,
        codec,
        credentials,
        snapshot,
        (claims, request) -> true,
        presence,
        jdbc,
        jdbc,
        gate,
        clock,
        sensorConfigured,
        Duration.ofSeconds(skew),
        Duration.ofSeconds(snapshotAge),
        "tmc",
        "tmc-gates"
    );
    return new VerifyAccessUseCase(steps, jdbc, jdbc, clock);
  }
}
