package tmc.shared;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fictional personnel used by the workflow demo. */
public final class StaffDirectory {
  public enum Group { STAFF, MANAGER, EXECUTIVE }
  public record Employee(String id, String name, Group accessGroup, String departmentId,
      String shiftId, String siteId, boolean active) {}

  private static final List<Employee> EMPLOYEES = List.of(
      new Employee("NV001", "Nguyễn Văn An", Group.STAFF, "PRODUCTION", "STANDARD", "SITE-BD", true),
      new Employee("NV002", "Trần Thị Mai", Group.STAFF, "PRODUCTION", "STANDARD", "SITE-BD", true),
      new Employee("NV003", "Lê Minh Khôi", Group.MANAGER, "PRODUCTION", "STANDARD", "SITE-BD", true),
      new Employee("NV004", "Phạm Thu Hà", Group.MANAGER, "OPERATIONS", "STANDARD", "SITE-BD", true),
      new Employee("NV005", "Võ Quốc Huy", Group.EXECUTIVE, "DIRECTORS", "STANDARD", "SITE-BD", true),
      new Employee("NV006", "Đặng Ngọc Linh", Group.STAFF, "PRODUCTION", "NIGHT", "SITE-LA", true)
  );

  private StaffDirectory() {}

  public static List<Employee> employees() { return EMPLOYEES; }

  public static Optional<Employee> find(String id) {
    return EMPLOYEES.stream().filter(employee -> Objects.equals(employee.id(), id)).findFirst();
  }

  public static String name(String id) { return find(id).map(Employee::name).orElse(id); }

  public static String siteFor(String site) { return "SITE-1".equals(site) ? "SITE-BD" : site; }
}
