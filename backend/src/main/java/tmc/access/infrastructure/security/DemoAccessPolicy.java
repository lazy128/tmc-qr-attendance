package tmc.access.infrastructure.security;

import java.util.Map;
import java.util.Set;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;

/** Sample identities and assignments used by the local demo. */
public final class DemoAccessPolicy implements PermissionPolicy, ScopeResolver {
  private static final Set<String> EMPLOYEES = Set.of("demo-employee", "demo-employee-2");
  private static final Map<String, Set<String>> PERMISSIONS = Map.of(
      "employee", Set.of("attendance:read", "qr:issue"),
      "employee-2", Set.of("attendance:read", "qr:issue"),
      "manager", Set.of("attendance:read"),
      "hr", Set.of("attendance:read", "attendance:export", "qr:revoke", "qr:enroll-device"),
      "guard", Set.of("presence:correct", "stations:read-assigned"),
      "admin", Set.of("system:configure", "stations:manage")
  );

  @Override
  public boolean permits(String userId, String permission) {
    return PERMISSIONS.getOrDefault(userId, Set.of()).contains(permission);
  }

  @Override
  public Set<String> visibleEmployeeIds(String userId, String scope) {
    return switch (scope) {
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
}
