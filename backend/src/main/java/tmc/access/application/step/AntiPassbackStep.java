package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.PresenceTracker;

public final class AntiPassbackStep implements VerificationStep {
  private final PresenceTracker presence;

  public AntiPassbackStep(PresenceTracker presence) {
    this.presence = presence;
  }

  @Override
  public int order() {
    return 60;
  }

  @Override
  public String name() {
    return "AntiPassback";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    return presence.allowed(request.siteId().value(), context.employeeId, request.direction())
        ? StepResult.ok()
        : StepResult.deny(Reason.ANTI_PASSBACK);
  }
}
