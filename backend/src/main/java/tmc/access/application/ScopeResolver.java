package tmc.access.application;

import java.util.Set;

public interface ScopeResolver {
  Set<String> visibleEmployeeIds(String userId, String scope);
}
