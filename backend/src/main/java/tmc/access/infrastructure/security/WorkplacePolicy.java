package tmc.access.infrastructure.security;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;
import tmc.shared.StaffDirectory;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.auth.WorkplaceAccounts;
import tmc.shared.auth.WorkplaceAccounts.Role;

public final class WorkplacePolicy implements PermissionPolicy, ScopeResolver {
  private static final Map<Role, Set<String>> PERMISSIONS = Map.of(
      Role.EMPLOYEE, Set.of("attendance:read", "qr:issue"),
      Role.MANAGER, Set.of("attendance:read", "qr:issue"),
      Role.HR, Set.of("attendance:read", "attendance:export", "attendance:correct", "qr:revoke", "qr:enroll-device"),
      Role.GUARD, Set.of("stations:read-assigned", "presence:correct"),
      Role.ADMIN, Set.of("stations:manage", "system:configure", "attendance:read", "attendance:export", "attendance:correct"));
  private final WorkplaceAccounts accounts;

  public WorkplacePolicy(WorkplaceAccounts accounts) { this.accounts = accounts; }

  @Override public boolean permits(String actor, String permission) {
    return accounts.account(actor).filter(account -> account.enabled() && !account.changeRequired())
        .map(account -> PERMISSIONS.get(account.role()).contains(permission)).orElse(false);
  }

  @Override public Set<String> visibleEmployeeIds(String actor, String scope) {
    var account = accounts.account(actor).filter(item -> item.enabled() && !item.changeRequired()).orElse(null);
    if (account == null) return Set.of();
    return switch (account.role()) {
      case EMPLOYEE -> Set.of(account.employeeId());
      case MANAGER -> {
        if (scope.equals("Own")) yield Set.of(account.employeeId());
        var manager = accounts.find(account.employeeId()).orElseThrow();
        yield accounts.employees().stream().filter(person -> StaffDirectory.siteFor(person.siteId()).equals(StaffDirectory.siteFor(manager.siteId()))
            && person.departmentId().equals(manager.departmentId())).map(Employee::id).collect(Collectors.toUnmodifiableSet());
      }
      case HR, ADMIN -> accounts.employees().stream().map(Employee::id).collect(Collectors.toUnmodifiableSet());
      default -> Set.of();
    };
  }
}
