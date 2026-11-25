package tmc.bootstrap.worker;

import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.infrastructure.snapshot.FileSnapshotProvider;
import tmc.shared.clock.ClockProvider;

@Component
public class SnapshotReloadWorker {
  private final SnapshotProvider provider;
  private final ClockProvider clock;

  public SnapshotReloadWorker(SnapshotProvider provider, ClockProvider clock) {
    this.provider = provider;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${tmc.edge.snapshot-reload-millis:10000}")
  public void reload() {
    if (provider instanceof FileSnapshotProvider file) {
      try {
        file.reload(clock.now());
      } catch (IOException invalid) {
        LoggerFactory.getLogger(SnapshotReloadWorker.class).warn("Snapshot reload failed; keeping previous timestamp");
      }
    }
  }
}
