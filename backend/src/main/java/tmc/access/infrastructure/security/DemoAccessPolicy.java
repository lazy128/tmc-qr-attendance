package tmc.access.infrastructure.security;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;
import tmc.shared.StaffDirectory;

/** Sample identities and assignments used by the local demo. */
public final class DemoAccessPolicy implements PermissionPolicy, ScopeResolver {
  private static final Set<String> EMPLOYEES = Set.of("demo-employee", "demo-employee-2");
  private static final Map<String, Set<String>> PERMISSIONS = Map.of(
      "employee", Set.of("attendance:read", "qr:issue"),
      "employee-2", Set.of("attendance:read", "qr:issue"),
      "manager", Set.of("attendance:read"),
      "hr", Set.of("attendance:read", "attendance:export", "attendance:correct", "qr:revoke", "qr:enroll-device"),
      "guard", Set.of("presence:correct", "stations:read-assigned"),
      "admin", Set.of("system:configure", "stations:manage")
  );

  @Override
  public boolean permits(String userId, String permission) {
    if ("work-hr".equals(userId)) {
      return Set.of("attendance:read", "attendance:export", "attendance:correct").contains(permission);
    }
    if ("work-manager".equals(userId)) return "attendance:read".equals(permission);
    if (userId.startsWith("work-") && StaffDirectory.find(userId.substring(5)).isPresent()) {
      return "attendance:read".equals(permission);
    }
    return PERMISSIONS.getOrDefault(userId, Set.of()).contains(permission);
  }

  @Override
  public Set<String> visibleEmployeeIds(String userId, String scope) {
    return switch (scope) {
      case "Timesheet" -> timesheetScope(userId);
      case "Own" -> switch (userId) {
        case "employee" -> Set.of("demo-employee");
        case "employee-2" -> Set.of("demo-employee-2");
        default -> Set.of();
      };
      case "Team" -> "manager".equals(userId) ? Set.of("demo-employee-2") : Set.of();
      case "Authorized" -> "hr".equals(userId) ? EMPLOYEES : Set.of();
      default -> Set.of();
    };
  }

  private Set<String> timesheetScope(String actor) {
    if ("work-hr".equals(actor)) {
      return StaffDirectory.employees().stream().map(StaffDirectory.Employee::id).collect(Collectors.toUnmodifiableSet());
    }
    if ("work-manager".equals(actor)) {
      return StaffDirectory.employees().stream()
          .filter(item -> item.siteId().equals("SITE-BD") && item.departmentId().equals("PRODUCTION"))
          .map(StaffDirectory.Employee::id).collect(Collectors.toUnmodifiableSet());
    }
    if (actor.startsWith("work-")) {
      return StaffDirectory.find(actor.substring(5)).map(employee -> Set.of(employee.id())).orElse(Set.of());
    }
    return switch (actor) {
      case "employee", "employee-2" -> visibleEmployeeIds(actor, "Own");
      case "manager" -> visibleEmployeeIds(actor, "Team");
      default -> visibleEmployeeIds(actor, "Authorized");
    };
  }
}
