package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Channel;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.PresenceTracker;
import tmc.access.domain.port.UsedTokenStore;

public final class AntiPassbackStep implements VerificationStep {
  private final PresenceTracker presence;
  private final UsedTokenStore usedTokens;

  public AntiPassbackStep(PresenceTracker presence, UsedTokenStore usedTokens) {
    this.presence = presence;
    this.usedTokens = usedTokens;
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
    if (!context.attendanceGate || request.channel() == Channel.PERSONAL_SELF) return StepResult.ok();
    var site = request.siteId().value();
    var denial = presence.denial(site, context.employeeId, request.direction(), request.scannedAt());
    if (denial == null) return StepResult.ok();
    // A concurrent scan can commit after the first replay check and before this presence read.
    return StepResult.deny(usedTokens.alreadyConsumed(site, context.claims.jti()) ? Reason.REPLAY : denial);
  }
}
