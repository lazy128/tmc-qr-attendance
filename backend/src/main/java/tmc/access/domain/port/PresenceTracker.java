package tmc.access.domain.port;

import java.time.Instant;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;

public interface PresenceTracker {
  boolean allowed(String siteId, String employeeId, Direction direction);

  default Reason denial(String siteId, String employeeId, Direction direction, Instant scannedAt) {
    return allowed(siteId, employeeId, direction) ? null : Reason.ANTI_PASSBACK;
  }

  void acknowledged(String siteId, String employeeId, Direction direction, String eventId);

  void confirmed(String siteId, String employeeId, Direction direction, String eventId);
}
