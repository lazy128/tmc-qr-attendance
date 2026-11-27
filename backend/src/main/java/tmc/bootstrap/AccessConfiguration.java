package tmc.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tmc.access.application.RecordPassageEvidence;
import tmc.access.application.CompositePolicyEvaluator;
import tmc.access.application.GateRules.ActiveEmployeeRule;
import tmc.access.application.GateRules.SiteRule;
import tmc.access.application.GateRules.ZoneGroupRule;
import tmc.access.application.GateRules.ShiftWindowRule;
import tmc.access.application.GateRules.Window;
import tmc.access.domain.Direction;
import tmc.access.domain.Gate;
import tmc.access.application.VerificationPipeline;
import tmc.access.application.VerifyAccessUseCase;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.GateResult;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.domain.port.Claims;
import tmc.access.domain.AccessRequest;
import tmc.access.infrastructure.security.Es256TokenCodec;
import tmc.access.infrastructure.turnstile.FakeTurnstileAdapter;
import tmc.access.infrastructure.turnstile.SoftwareAttendanceAdapter;
import tmc.access.infrastructure.snapshot.FileSnapshotProvider;
import tmc.access.infrastructure.persistence.JdbcCredentialStore;
import tmc.access.infrastructure.persistence.JdbcPresenceTracker;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.access.infrastructure.turnstile.MeteredGateCommandSender;
import tmc.access.infrastructure.snapshot.MutableSnapshot;
import tmc.access.infrastructure.turnstile.TimeoutGateCommandSender;
import tmc.attendance.application.TimesheetCalculator;
import tmc.shared.clock.ClockProvider;
import tmc.shared.EmployeeDirectory;
import tmc.shared.StaffDirectory;
import tmc.shared.WorkplaceSites;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.StaffDirectory.Group;

@Configuration
public class AccessConfiguration {
  @Bean
  JdbcCredentialStore credentialStore(JdbcTemplate jdbc, @Value("${tmc.accounts.enabled:false}") boolean workplace) {
    var store = new JdbcCredentialStore(jdbc);
    if (workplace) return store;
    store.seedIfMissing(new Credential("demo-credential", "demo-employee", 1, true, "demo-device"));
    store.seedIfMissing(new Credential("demo-credential-2", "demo-employee-2", 1, true, "demo-device-2"));
    return store;
  }

  @Bean
  JdbcPresenceTracker presenceTracker(JdbcTemplate jdbc, TimesheetCalculator calculator,
      @Value("${tmc.accounts.enabled:false}") boolean workplace) {
    return workplace ? new JdbcPresenceTracker(jdbc, true, time -> calculator.start(calculator.workDate(time)))
        : new JdbcPresenceTracker(jdbc);
  }

  @Bean
  SnapshotProvider snapshot(
      ClockProvider clock, ObjectMapper json, JdbcCredentialStore credentials, EmployeeDirectory employees,
      @Value("${tmc.edge.site:SITE-1}") String site,
      @Value("${tmc.edge.snapshot-file:}") String snapshotFile,
      @Value("${tmc.edge.local-demo-authority:true}") boolean localAuthority,
      @Value("${tmc.accounts.enabled:false}") boolean workplace
  ) {
    if (!snapshotFile.isBlank()) return new FileSnapshotProvider(Path.of(snapshotFile), json);
    if (!localAuthority) return new MutableSnapshot(Instant.EPOCH);
    // The isolated demo reads authoritative credentials and revocations directly from local H2.
    return new SnapshotProvider() {
      @Override public Instant snapshotTime() { return clock.now(); }
      @Override public boolean revoked(String credentialId) { return false; }
      @Override public boolean policyAllows(Claims claims, AccessRequest request) { return true; }
      @Override public Optional<Employee> employeeForCredential(String id) {
        return credentials.getCredential(id).flatMap(credential -> {
          var employee = employees.find(credential.employeeId());
          if (workplace || employee.isPresent()) return employee;
          return Optional.of(new Employee(credential.employeeId(), credential.employeeId(), Group.STAFF,
              "LOCAL", "STANDARD", site, credential.active()));
        });
      }
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

  @Bean("recordingAdapter")
  @ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "false", matchIfMissing = true)
  FakeTurnstileAdapter turnstile() {
    return new FakeTurnstileAdapter(GateResult.ACKED);
  }

  @Bean("recordingAdapter")
  @ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "true")
  SoftwareAttendanceAdapter softwareAttendance() { return new SoftwareAttendanceAdapter(); }

  @Bean(destroyMethod = "close")
  TimeoutGateCommandSender boundedTurnstile(
      @Qualifier("recordingAdapter") GateCommandSender adapter,
      @Value("${tmc.turnstile.timeout-millis:800}") long timeout
  ) {
    return new TimeoutGateCommandSender(adapter, timeout);
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
  JdbcStationRegistry stationRegistry(JdbcTemplate jdbc,
      @Value("${tmc.edge.station:GATE-1}") String station,
      @Value("${tmc.edge.site:SITE-1}") String site,
      @Value("${tmc.workflow.demo:false}") boolean demo,
      @Value("${tmc.accounts.enabled:false}") boolean workplace) {
    if (workplace) {
      String primarySite = StaffDirectory.siteFor(site);
      var registry = new JdbcStationRegistry(jdbc, primarySite, station,
          WorkplaceSites.ALL.stream().map(WorkplaceSites.Site::id).toList());
      for (var plant : WorkplaceSites.ALL) {
        seedAttendanceGates(registry, plant.id(), plant.name(),
            plant.id().equals(primarySite) ? station : plant.entryStation());
      }
      return registry;
    }
    var registry = new JdbcStationRegistry(jdbc, site, station);
    if (!demo) return registry;
    String location = "SITE-LA".equals(site) ? "Long An" : "Bình Dương";
    seedAttendanceGates(registry, site, location, station);
    registry.seedGate(station + "-PROD", new Gate(location + " – Khu sản xuất", site, Gate.Type.ZONE_GATE,
        Set.of(Group.STAFF, Group.MANAGER), Set.of(Direction.ENTRY), null, null));
    registry.seedGate(station + "-OFFICE", new Gate(location + " – Văn phòng quản lý", site, Gate.Type.ZONE_GATE,
        Set.of(Group.MANAGER, Group.EXECUTIVE), Set.of(Direction.ENTRY), null, null));
    registry.seedGate(station + "-EXEC", new Gate(location + " – Khu lãnh đạo", site, Gate.Type.ZONE_GATE,
        Set.of(Group.EXECUTIVE), Set.of(Direction.ENTRY), null, null));
    return registry;
  }

  private void seedAttendanceGates(JdbcStationRegistry registry, String site, String location, String station) {
    var all = EnumSet.allOf(Group.class);
    registry.seedGate(station, new Gate(location + " – Cổng vào 1", site, Gate.Type.ATTENDANCE_GATE,
        all, Set.of(Direction.ENTRY), null, null));
    registry.seedGate(station + "-OUT", new Gate(location + " – Cổng ra 1", site, Gate.Type.ATTENDANCE_GATE,
        all, Set.of(Direction.EXIT), null, null));
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
      MeterRegistry metrics,
      JdbcStationRegistry stations,
      @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone,
      @Value("${tmc.access.shift-grace-minutes:10}") long graceMinutes,
      @Value("${tmc.access.enforce-employee-shift:false}") boolean enforceShift,
      @Value("${tmc.access.shift.standard-open:06:00}") String standardOpen,
      @Value("${tmc.access.shift.standard-close:19:00}") String standardClose,
      @Value("${tmc.access.shift.night-open:20:00}") String nightOpen,
      @Value("${tmc.access.shift.night-close:06:00}") String nightClose,
      @Value("${tmc.edge.passage-sensor:false}") boolean sensorConfigured,
      @Value("${tmc.clock.skew-seconds:5}") long skew,
      @Value("${tmc.edge.snapshot-max-age-seconds:900}") long snapshotAge
  ) {
    var shifts = enforceShift ? Map.of(
        "STANDARD", new Window(LocalTime.parse(standardOpen), LocalTime.parse(standardClose)),
        "NIGHT", new Window(LocalTime.parse(nightOpen), LocalTime.parse(nightClose))) : Map.<String, Window>of();
    var steps = VerificationPipeline.create(
        stations,
        stations,
        codec,
        credentials,
        snapshot,
        new CompositePolicyEvaluator(List.of(new ActiveEmployeeRule(), new SiteRule(), new ZoneGroupRule(),
            new ShiftWindowRule(ZoneId.of(zone), Duration.ofMinutes(graceMinutes), shifts)), snapshot,
            id -> stations.find(id.value()).map(JdbcStationRegistry.Station::gate), clock),
        presence,
        jdbc,
        jdbc,
        new MeteredGateCommandSender(gate, metrics),
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
