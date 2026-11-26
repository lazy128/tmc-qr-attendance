package tmc.shared;

import java.util.Optional;

@FunctionalInterface
public interface EmployeeDirectory {
  Optional<StaffDirectory.Employee> find(String id);

  default String name(String id) { return find(id).map(StaffDirectory.Employee::name).orElse(id); }
}
