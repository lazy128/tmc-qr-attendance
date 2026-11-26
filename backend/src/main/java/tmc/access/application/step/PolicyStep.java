package tmc.access.application.step;

import java.time.Duration;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.PolicyEvaluator;
import tmc.access.domain.port.SnapshotProvider;
import tmc.shared.clock.ClockProvider;

public final class PolicyStep implements VerificationStep {
  private final PolicyEvaluator policy;
  private final SnapshotProvider snapshot;
  private final ClockProvider clock;
  private final Duration snapshotMaxAge;

  public PolicyStep(
      PolicyEvaluator policy,
      SnapshotProvider snapshot,
      ClockProvider clock,
      Duration snapshotMaxAge
  ) {
    this.policy = policy;
    this.snapshot = snapshot;
    this.clock = clock;
    this.snapshotMaxAge = snapshotMaxAge;
  }

  @Override
  public int order() {
    return 50;
  }

  @Override
  public String name() {
    return "Policy";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    if (!snapshot.isFreshAt(clock.now(), snapshotMaxAge)) {
      return StepResult.deny(Reason.SNAPSHOT_STALE);
    }
    context.attendanceGate = policy.isAttendanceGate(request);
    var denial = policy.denial(context.claims, request);
    if (denial.isPresent()) return StepResult.deny(denial.get());
    return snapshot.policyAllows(context.claims, request) ? StepResult.ok() : StepResult.deny(Reason.POLICY_DENIED);
  }
}
