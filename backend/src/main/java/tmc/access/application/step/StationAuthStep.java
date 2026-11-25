package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.application.port.StationAuthenticator;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;

public final class StationAuthStep implements VerificationStep {
  private final StationAuthenticator stationAuth;

  public StationAuthStep(StationAuthenticator stationAuth) {
    this.stationAuth = stationAuth;
  }

  @Override
  public int order() {
    return 10;
  }

  @Override
  public String name() {
    return "StationAuth";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    return stationAuth.authenticated(request)
        ? StepResult.ok()
        : StepResult.deny(Reason.STATION_AUTH_FAILED);
  }
}
