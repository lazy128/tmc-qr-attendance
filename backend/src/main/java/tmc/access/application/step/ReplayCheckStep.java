package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.UsedTokenStore;

public final class ReplayCheckStep implements VerificationStep {
  private final UsedTokenStore usedTokens;

  public ReplayCheckStep(UsedTokenStore usedTokens) {
    this.usedTokens = usedTokens;
  }

  @Override public int order() { return 55; }
  @Override public String name() { return "ReplayCheck"; }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    return usedTokens.alreadyConsumed(request.siteId().value(), context.claims.jti())
        ? StepResult.deny(Reason.REPLAY)
        : StepResult.ok();
  }
}
