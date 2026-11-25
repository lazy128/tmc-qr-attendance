package tmc.access.domain.port;

import java.time.Duration;
import java.time.Instant;
import tmc.access.domain.AccessRequest;

public interface SnapshotProvider {
  Instant snapshotTime();

  default boolean isFreshAt(Instant now, Duration maxAge) {
    return Duration.between(snapshotTime(), now).compareTo(maxAge) <= 0;
  }

  boolean revoked(String credentialId);

  boolean policyAllows(Claims claims, AccessRequest request);
}
