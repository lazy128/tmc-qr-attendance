package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.application.port.StationScope;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;

public final class StationScopeStep implements VerificationStep {
  private final StationScope scope;

  public StationScopeStep(StationScope scope) {
    this.scope = scope;
  }

  @Override
  public int order() {
    return 20;
  }

  @Override
  public String name() {
    return "StationScope";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    context.stationSite = scope.siteFor(request.stationId());
    return scope.allowed(request.stationId(), request.siteId())
        ? StepResult.ok()
        : StepResult.deny(Reason.STATION_SCOPE);
  }
}
