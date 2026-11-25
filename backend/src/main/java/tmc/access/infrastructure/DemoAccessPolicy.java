package tmc.access.infrastructure;

import java.util.Map;
import java.util.Set;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;

/** Sample identities and assignments used by the local demo. */
public final class DemoAccessPolicy implements PermissionPolicy, ScopeResolver {
  private static final Set<String> EMPLOYEES = Set.of("demo-employee", "demo-employee-2");
  private static final Map<String, Set<String>> PERMISSIONS = Map.of(
      "employee", Set.of("attendance:read"),
      "manager", Set.of("attendance:read"),
      "hr", Set.of("attendance:read", "attendance:export"),
      "guard", Set.of("presence:correct"),
      "admin", Set.of("system:configure")
  );

  @Override
  public boolean permits(String userId, String permission) {
    return PERMISSIONS.getOrDefault(userId, Set.of()).contains(permission);
  }

  @Override
  public Set<String> visibleEmployeeIds(String userId, String scope) {
    return switch (scope) {
      case "Own" -> "employee".equals(userId) ? Set.of("demo-employee") : Set.of();
      case "Team" -> "manager".equals(userId) ? Set.of("demo-employee-2") : Set.of();
      case "Authorized" -> "hr".equals(userId) ? EMPLOYEES : Set.of();
      default -> Set.of();
    };
  }
}
