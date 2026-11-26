package tmc.access.infrastructure.security;

import java.util.Objects;
import tmc.access.application.port.BiometricVerifier;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

/** A fixed outcome for tests. The gate pipeline does not invoke biometrics in the demo. */
public final class FakeBiometricVerifier implements BiometricVerifier {
  private final FaceMatch outcome;

  public FakeBiometricVerifier() {
    this(FaceMatch.SKIPPED);
  }

  public FakeBiometricVerifier(FaceMatch outcome) {
    this.outcome = Objects.requireNonNull(outcome);
  }

  @Override
  public FaceMatch verify(String employeeId, SiteId siteId, StationId stationId) {
    return outcome;
  }
}
