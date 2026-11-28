package tmc.bootstrap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.shared.clock.ClockProvider;

@Component("edgeHealthIndicator")
public class EdgeHealthIndicator implements HealthIndicator, MeterBinder {
  private static final Logger LOG = LoggerFactory.getLogger(EdgeHealthIndicator.class);
  private final SnapshotProvider snapshot;
  private final ClockProvider clock;
  private final Duration snapshotMaxAge;
  private final JdbcAttemptStore attempts;
  private final boolean centralEnabled;
  private final Duration warningLead;
  private Freshness lastReported;

  public EdgeHealthIndicator(
      SnapshotProvider snapshot, ClockProvider clock, JdbcAttemptStore attempts,
      @Value("${tmc.edge.snapshot-max-age-seconds:900}") long snapshotMaxAge,
      @Value("${tmc.edge.snapshot-warning-seconds:120}") long warningSeconds,
      @Value("${tmc.central.enabled:false}") boolean centralEnabled
  ) {
    this.snapshot = snapshot;
    this.clock = clock;
    this.attempts = attempts;
    this.snapshotMaxAge = Duration.ofSeconds(snapshotMaxAge);
    this.warningLead = Duration.ofSeconds(Math.max(0, Math.min(snapshotMaxAge, warningSeconds)));
    this.centralEnabled = centralEnabled;
  }

  @Override
  public Health health() {
    var remaining = snapshotRemaining();
    var freshness = freshness(remaining);
    try {
      return (freshness == Freshness.STALE ? Health.outOfService() : Health.up())
          .withDetail("snapshot", freshness.name())
          .withDetail("snapshotSecondsRemaining", Math.max(0, remaining.toSeconds()))
          .withDetail("outboxDepth", attempts.pendingOutboxDepth(centralEnabled))
          .build();
    } catch (DataAccessException unavailable) {
      return Health.down().withDetail("snapshot", freshness.name()).withDetail("outboxDepth", "UNAVAILABLE").build();
    }
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("tmc.edge.snapshot.fresh", this, edge -> edge.snapshotRemaining().isNegative() ? 0 : 1)
        .description("Snapshot freshness: 1 is FRESH, 0 is STALE")
        .register(registry);
    Gauge.builder("tmc.edge.snapshot.seconds.remaining", this, edge -> Math.max(0, edge.snapshotRemaining().toSeconds()))
        .description("Seconds left before the policy snapshot expires")
        .register(registry);
    Gauge.builder("tmc.edge.outbox.depth", this, EdgeHealthIndicator::outboxDepth)
        .description("Distinct outbox rows waiting for enabled deliveries or command resolution")
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${tmc.edge.snapshot-alert-delay-ms:5000}")
  public void reportSnapshotStatus() {
    var remaining = snapshotRemaining();
    var current = freshness(remaining);
    if (current == lastReported) return;
    if (current == Freshness.EXPIRING) LOG.warn("Edge policy snapshot expires in {} seconds", remaining.toSeconds());
    if (current == Freshness.STALE) LOG.warn("Edge policy snapshot expired; scans denied until policy refresh");
    if (current == Freshness.FRESH && lastReported != null) LOG.info("Edge policy snapshot refreshed");
    lastReported = current;
  }

  private Duration snapshotRemaining() {
    var age = Duration.between(snapshot.snapshotTime(), clock.now());
    return age.isNegative() ? snapshotMaxAge : snapshotMaxAge.minus(age);
  }

  private Freshness freshness(Duration remaining) {
    if (remaining.isNegative()) return Freshness.STALE;
    return remaining.compareTo(warningLead) <= 0 ? Freshness.EXPIRING : Freshness.FRESH;
  }

  private enum Freshness { FRESH, EXPIRING, STALE }

  private double outboxDepth() {
    try {
      return attempts.pendingOutboxDepth(centralEnabled);
    } catch (DataAccessException unavailable) {
      return Double.NaN;
    }
  }
}
