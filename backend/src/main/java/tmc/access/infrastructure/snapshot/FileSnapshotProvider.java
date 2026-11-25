package tmc.access.infrastructure.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.SnapshotProvider;

/** Reads a local file supplied by the deployment's trusted snapshot publisher. */
public final class FileSnapshotProvider implements SnapshotProvider {
  private final Path path;
  private final ObjectMapper json;
  private volatile Snapshot current = new Snapshot(Instant.EPOCH, Set.of(), Map.of());

  public FileSnapshotProvider(Path path, ObjectMapper json) {
    this.path = path;
    this.json = json;
  }

  public void reload(Instant now) throws IOException {
    Snapshot next = json.readValue(path.toFile(), Snapshot.class);
    if (next.generatedAt() == null || next.revoked() == null || next.stationSites() == null
        || next.generatedAt().isAfter(now)) {
      throw new IOException("Missing or invalid snapshot fields");
    }
    if (next.generatedAt().isAfter(current.generatedAt())) current = next;
  }

  @Override public Instant snapshotTime() { return current.generatedAt(); }
  @Override public boolean revoked(String credentialId) { return current.revoked().contains(credentialId); }

  @Override
  public boolean policyAllows(Claims claims, AccessRequest request) {
    return request.siteId().value().equals(current.stationSites().get(request.stationId().value()));
  }

  public record Snapshot(Instant generatedAt, Set<String> revoked, Map<String, String> stationSites) {}
}
