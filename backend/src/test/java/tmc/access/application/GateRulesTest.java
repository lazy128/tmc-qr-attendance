package tmc.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.Gate;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.SnapshotProvider;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.StaffDirectory.Group;

class GateRulesTest {
  private static final Instant NOW = Instant.parse("2026-06-20T01:00:00Z");
  private static final Set<Direction> ENTRY = Set.of(Direction.ENTRY);
  private static final AccessRequest REQUEST = new AccessRequest(new EventId("scan"), "test", new SiteId("SITE-BD"),
      new StationId("STATION"), Direction.ENTRY, NOW, "token", "device");
  private static final Claims CLAIMS = new Claims("tmc", "gates", "credential", "jti", NOW, NOW, NOW,
      1, "device", "demo", null);
  private static final List<AccessRule> RULES = List.of(new GateRules.ActiveEmployeeRule(), new GateRules.SiteRule(),
      new GateRules.ZoneGroupRule(), new GateRules.ShiftWindowRule(ZoneId.of("Asia/Ho_Chi_Minh"), Duration.ofMinutes(10)));

  @Test void matrixUsesExplicitGroupsWithNoInheritance() {
    var groups = Map.of("production", Set.of(Group.STAFF, Group.MANAGER),
        "office", Set.of(Group.MANAGER, Group.EXECUTIVE), "executive", Set.of(Group.EXECUTIVE));
    for (var area : groups.entrySet()) {
      for (var group : Group.values()) {
        var employee = employee(group, "SITE-BD", true);
        var gate = new Gate(area.getKey(), "SITE-BD", Gate.Type.ZONE_GATE, area.getValue(), ENTRY, null, null);
        var policy = policy(RULES, employee, gate);
        assertThat(policy.isAttendanceGate(REQUEST)).isFalse();
        assertThat(policy.denial(CLAIMS, REQUEST)).isEqualTo(area.getValue().contains(group)
            ? Optional.empty() : Optional.of(Reason.ZONE_DENIED));
      }
    }
  }

  @Test void handlesDifferentSiteInactiveStaffAndShiftWindow() {
    var gate = new Gate("Main", "SITE-BD", Gate.Type.ATTENDANCE_GATE, EnumSet.allOf(Group.class),
        ENTRY, LocalTime.of(9, 0), LocalTime.of(18, 0));
    assertThat(policy(RULES, employee(Group.STAFF, "SITE-LA", true), gate).denial(CLAIMS, REQUEST))
        .contains(Reason.STATION_SCOPE);
    assertThat(policy(RULES, employee(Group.STAFF, "SITE-BD", false), gate).denial(CLAIMS, REQUEST))
        .contains(Reason.EMPLOYEE_INACTIVE);
    assertThat(policy(RULES, employee(Group.STAFF, "SITE-BD", true), gate).denial(CLAIMS, REQUEST))
        .contains(Reason.OUTSIDE_SHIFT);
    var grace = new Gate("Main", "SITE-BD", Gate.Type.ATTENDANCE_GATE, EnumSet.allOf(Group.class),
        ENTRY, LocalTime.of(8, 5), LocalTime.of(18, 0));
    assertThat(policy(RULES, employee(Group.STAFF, "SITE-BD", true), grace).denial(CLAIMS, REQUEST)).isEmpty();
  }

  @Test void canRegisterAnExtraRuleWithoutEditingTheComposite() {
    var gate = new Gate("Test", "SITE-BD", Gate.Type.ATTENDANCE_GATE, EnumSet.allOf(Group.class), ENTRY, null, null);
    var extra = new java.util.ArrayList<>(RULES);
    extra.add((employee, selectedGate, request, at) -> Optional.of(Reason.POLICY_DENIED));
    assertThat(policy(extra, employee(Group.STAFF, "SITE-BD", true), gate).denial(CLAIMS, REQUEST))
        .contains(Reason.POLICY_DENIED);
  }

  private Employee employee(Group group, String site, boolean active) {
    return new Employee("NV", "Demo", group, "DEPT", "STANDARD", site, active);
  }

  private CompositePolicyEvaluator policy(List<AccessRule> rules, Employee employee, Gate gate) {
    var snapshot = new SnapshotProvider() {
      @Override public Instant snapshotTime() { return NOW; }
      @Override public boolean revoked(String id) { return false; }
      @Override public boolean policyAllows(Claims claims, AccessRequest request) { return true; }
      @Override public Optional<Employee> employeeForCredential(String id) { return Optional.of(employee); }
    };
    return new CompositePolicyEvaluator(rules, snapshot, id -> Optional.of(gate), () -> NOW);
  }
}
