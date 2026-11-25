package tmc.access.domain.port;

import java.time.Instant;

public interface PresenceCorrectionStore {
  boolean correctMissedExit(String expectedEventId, String site, String station, String actor, String correlation, Instant now);
}
