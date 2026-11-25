package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.AttemptStore;

public final class OutcomeRecordStep implements VerificationStep {
  private final AttemptStore attempts;

  public OutcomeRecordStep(AttemptStore attempts) {
    this.attempts = attempts;
  }

  @Override
  public int order() {
    return 90;
  }

  @Override
  public String name() {
    return "OutcomeRecord";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    if (context.attempt == null) {
      return StepResult.ok();
    }
    try {
      attempts.recordOutcome(context.attempt);
    } catch (RuntimeException persistFailed) {
      return StepResult.deny(Reason.PERSIST_FAILED);
    }
    return StepResult.ok();
  }
}
