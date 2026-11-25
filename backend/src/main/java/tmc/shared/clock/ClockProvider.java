package tmc.shared.clock;

import java.time.Instant;

public interface ClockProvider {
  Instant now();
}
