package tmc.access.domain.port;

import java.time.Instant;
import tmc.access.domain.AccessRequest;

public interface SnapshotProvider {
  Instant snapshotTime();

  boolean revoked(String credentialId);

  boolean policyAllows(Claims claims, AccessRequest request);
}
