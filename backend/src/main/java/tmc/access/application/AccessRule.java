package tmc.access.application;

import java.time.Instant;
import java.util.Optional;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Gate;
import tmc.shared.StaffDirectory.Employee;

@FunctionalInterface
public interface AccessRule {
  Optional<Reason> evaluate(Employee employee, Gate gate, AccessRequest request, Instant now);
}
