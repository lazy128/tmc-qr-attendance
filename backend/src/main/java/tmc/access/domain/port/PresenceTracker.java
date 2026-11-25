package tmc.access.domain.port;

import tmc.access.domain.Direction;

public interface PresenceTracker {
  boolean allowed(String siteId, String employeeId, Direction direction);

  void acknowledged(String siteId, String employeeId, Direction direction, String eventId);

  void confirmed(String siteId, String employeeId, Direction direction, String eventId);
}
