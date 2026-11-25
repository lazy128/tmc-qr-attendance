package tmc.access.infrastructure.persistence;

import java.util.HashMap;
import java.util.Map;
import tmc.access.domain.Direction;
import tmc.access.domain.port.PresenceTracker;

public final class InMemoryPresenceTracker implements PresenceTracker {
  private final Map<String, Direction> presence = new HashMap<>();

  @Override
  public synchronized boolean allowed(String siteId, String employeeId, Direction direction) {
    Direction current = presence.get(siteId + ":" + employeeId);
    return current == null || current != direction;
  }

  @Override
  public synchronized void acknowledged(String siteId, String employeeId, Direction direction, String eventId) {
    presence.put(siteId + ":" + employeeId, direction);
  }

  @Override
  public synchronized void confirmed(String siteId, String employeeId, Direction direction, String eventId) {
    presence.put(siteId + ":" + employeeId, direction);
  }
}
