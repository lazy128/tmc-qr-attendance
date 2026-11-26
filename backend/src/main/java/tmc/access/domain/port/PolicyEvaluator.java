package tmc.access.domain.port;

import java.util.Optional;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;

public interface PolicyEvaluator {
  boolean allows(Claims claims, AccessRequest request);

  default Optional<Reason> denial(Claims claims, AccessRequest request) {
    return allows(claims, request) ? Optional.empty() : Optional.of(Reason.POLICY_DENIED);
  }

  default boolean isAttendanceGate(AccessRequest request) { return true; }
}
