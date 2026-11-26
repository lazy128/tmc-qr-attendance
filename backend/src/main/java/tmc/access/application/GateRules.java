package tmc.access.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Map;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Gate;
import tmc.shared.StaffDirectory;
import tmc.shared.StaffDirectory.Employee;

public final class GateRules {
  private GateRules() {}

  public record ActiveEmployeeRule() implements AccessRule {
    @Override public Optional<Reason> evaluate(Employee employee, Gate gate, AccessRequest request, Instant now) {
      return denied(employee == null || !employee.active(), Reason.EMPLOYEE_INACTIVE);
    }
  }

  public record SiteRule() implements AccessRule {
    @Override public Optional<Reason> evaluate(Employee employee, Gate gate, AccessRequest request, Instant now) {
      return denied(!gate.siteId().equals(request.siteId().value())
          || !StaffDirectory.siteFor(gate.siteId()).equals(StaffDirectory.siteFor(employee.siteId())), Reason.STATION_SCOPE);
    }
  }

  public record ZoneGroupRule() implements AccessRule {
    @Override public Optional<Reason> evaluate(Employee employee, Gate gate, AccessRequest request, Instant now) {
      return denied((!gate.attendance() && "LOCAL".equals(employee.departmentId()))
          || !gate.allowedGroups().contains(employee.accessGroup())
          || !gate.directions().contains(request.direction()), Reason.ZONE_DENIED);
    }
  }

  public record Window(LocalTime open, LocalTime close) {}

  public record ShiftWindowRule(ZoneId zone, Duration grace, Map<String, Window> employeeShifts) implements AccessRule {
    public ShiftWindowRule {
      employeeShifts = Map.copyOf(employeeShifts);
    }

    public ShiftWindowRule(ZoneId zone, Duration grace) { this(zone, grace, Map.of()); }

    @Override public Optional<Reason> evaluate(Employee employee, Gate gate, AccessRequest request, Instant now) {
      var shift = employeeShifts.get(employee.shiftId());
      boolean outsideEmployeeShift = !employeeShifts.isEmpty() && (shift == null || !matches(shift, now));
      boolean outsideGateHours = gate.opensAt() != null && gate.closesAt() != null
          && !matches(new Window(gate.opensAt(), gate.closesAt()), now);
      return denied(outsideEmployeeShift || outsideGateHours, Reason.OUTSIDE_SHIFT);
    }

    private boolean matches(Window window, Instant now) {
      LocalDate today = now.atZone(zone).toLocalDate();
      return inWindow(today, window.open(), window.close(), now)
          || inWindow(today.minusDays(1), window.open(), window.close(), now)
          || inWindow(today.plusDays(1), window.open(), window.close(), now);
    }

    private boolean inWindow(LocalDate date, LocalTime open, LocalTime close, Instant now) {
      var start = date.atTime(open).atZone(zone).toInstant().minus(grace);
      var endDate = close.isAfter(open) ? date : date.plusDays(1);
      var end = endDate.atTime(close).atZone(zone).toInstant().plus(grace);
      return !now.isBefore(start) && !now.isAfter(end);
    }
  }

  private static Optional<Reason> denied(boolean condition, Reason reason) {
    return condition ? Optional.of(reason) : Optional.empty();
  }
}
