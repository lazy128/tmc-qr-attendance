package tmc.shared.clock;

import java.time.Instant;

public final class SystemClockProvider implements ClockProvider {
  @Override
  public Instant now() {
    return Instant.now();
  }
}
