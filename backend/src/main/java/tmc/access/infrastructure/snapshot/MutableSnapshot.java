package tmc.access.infrastructure.snapshot;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.SnapshotProvider;

public final class MutableSnapshot implements SnapshotProvider {
  private volatile Instant time;
  private final Set<String> revoked = ConcurrentHashMap.newKeySet();

  public MutableSnapshot(Instant time) {
    this.time = time;
  }

  @Override
  public Instant snapshotTime() {
    return time;
  }

  public void refresh(Instant at) {
    time = at;
  }

  public void revoke(String id) {
    revoked.add(id);
  }

  @Override
  public boolean revoked(String credentialId) {
    return revoked.contains(credentialId);
  }

  @Override
  public boolean policyAllows(Claims claims, AccessRequest request) {
    return true;
  }
}
