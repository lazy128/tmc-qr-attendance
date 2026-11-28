package tmc.access.domain.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import tmc.access.domain.AccessRequest;
import tmc.shared.StaffDirectory.Employee;

public interface SnapshotProvider {
  Duration MAX_FUTURE_SKEW = Duration.ofSeconds(5);

  Instant snapshotTime();

  default boolean isFreshAt(Instant now, Duration maxAge) {
    return !remainingAt(now, maxAge).isNegative();
  }

  default Duration remainingAt(Instant now, Duration maxAge) {
    var age = Duration.between(snapshotTime(), now);
    if (age.compareTo(MAX_FUTURE_SKEW.negated()) < 0) return age;
    return age.isNegative() ? maxAge : maxAge.minus(age);
  }

  boolean revoked(String credentialId);

  boolean policyAllows(Claims claims, AccessRequest request);

  default Optional<Employee> employeeForCredential(String credentialId) { return Optional.empty(); }
}
