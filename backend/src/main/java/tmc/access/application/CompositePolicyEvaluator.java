package tmc.access.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Gate;
import tmc.access.domain.StationId;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.PolicyEvaluator;
import tmc.access.domain.port.SnapshotProvider;
import tmc.shared.clock.ClockProvider;

public final class CompositePolicyEvaluator implements PolicyEvaluator {
  private final List<AccessRule> rules;
  private final SnapshotProvider snapshot;
  private final Function<StationId, Optional<Gate>> gates;
  private final ClockProvider clock;

  public CompositePolicyEvaluator(List<AccessRule> rules, SnapshotProvider snapshot,
      Function<StationId, Optional<Gate>> gates, ClockProvider clock) {
    this.rules = List.copyOf(rules);
    this.snapshot = snapshot;
    this.gates = gates;
    this.clock = clock;
  }

  @Override public boolean allows(Claims claims, AccessRequest request) {
    return denial(claims, request).isEmpty();
  }

  @Override public Optional<Reason> denial(Claims claims, AccessRequest request) {
    var gate = gates.apply(request.stationId());
    if (gate.isEmpty()) return Optional.of(Reason.STATION_SCOPE);
    var employee = snapshot.employeeForCredential(claims.credentialId()).orElse(null);
    var now = clock.now();
    for (AccessRule rule : rules) {
      var reason = rule.evaluate(employee, gate.get(), request, now);
      if (reason.isPresent()) return reason;
    }
    return Optional.empty();
  }

  @Override public boolean isAttendanceGate(AccessRequest request) {
    return gates.apply(request.stationId()).map(Gate::attendance).orElse(false);
  }
}
