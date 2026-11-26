package tmc.access.domain;

import java.time.LocalTime;
import java.util.Set;
import tmc.shared.StaffDirectory.Group;

public record Gate(String name, String siteId, Type type, Set<Group> allowedGroups, Set<Direction> directions,
    LocalTime opensAt, LocalTime closesAt) {
  public Gate {
    allowedGroups = Set.copyOf(allowedGroups);
    directions = Set.copyOf(directions);
  }

  public enum Type { ATTENDANCE_GATE, ZONE_GATE }
  public boolean attendance() { return type == Type.ATTENDANCE_GATE; }
}
