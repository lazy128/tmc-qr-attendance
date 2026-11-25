package tmc.bootstrap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.shared.clock.ClockProvider;

@Component("edgeHealthIndicator")
public class EdgeHealthIndicator implements HealthIndicator, MeterBinder {
  private final SnapshotProvider snapshot;
  private final ClockProvider clock;
  private final Duration snapshotMaxAge;
  private final JdbcAttemptStore attempts;
  private final boolean centralEnabled;

  public EdgeHealthIndicator(
      SnapshotProvider snapshot, ClockProvider clock, JdbcAttemptStore attempts,
      @Value("${tmc.edge.snapshot-max-age-seconds:900}") long snapshotMaxAge,
      @Value("${tmc.central.enabled:false}") boolean centralEnabled
  ) {
    this.snapshot = snapshot;
    this.clock = clock;
    this.attempts = attempts;
    this.snapshotMaxAge = Duration.ofSeconds(snapshotMaxAge);
    this.centralEnabled = centralEnabled;
  }

  @Override
  public Health health() {
    boolean fresh = snapshotIsFresh();
    String freshness = fresh ? "FRESH" : "STALE";
    try {
      return (fresh ? Health.up() : Health.outOfService())
          .withDetail("snapshot", freshness)
          .withDetail("outboxDepth", attempts.pendingOutboxDepth(centralEnabled))
          .build();
    } catch (DataAccessException unavailable) {
      return Health.down().withDetail("snapshot", freshness).withDetail("outboxDepth", "UNAVAILABLE").build();
    }
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("tmc.edge.snapshot.fresh", this, edge -> edge.snapshotIsFresh() ? 1 : 0)
        .description("Snapshot freshness: 1 is FRESH, 0 is STALE")
        .register(registry);
    Gauge.builder("tmc.edge.outbox.depth", this, EdgeHealthIndicator::outboxDepth)
        .description("Distinct outbox rows waiting for enabled deliveries or command resolution")
        .register(registry);
  }

  private boolean snapshotIsFresh() {
    return snapshot.isFreshAt(clock.now(), snapshotMaxAge);
  }

  private double outboxDepth() {
    try {
      return attempts.pendingOutboxDepth(centralEnabled);
    } catch (DataAccessException unavailable) {
      return Double.NaN;
    }
  }
}
