package tmc.access.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tmc.access.application.port.BiometricVerifier.FaceMatch;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

class FakeBiometricVerifierTest {
  @Test
  void skipsByDefaultAndSupportsEverySimulatedOutcome() {
    var site = new SiteId("SITE-1");
    var station = new StationId("GATE-1");
    assertThat(new FakeBiometricVerifier().verify("employee", site, station)).isEqualTo(FaceMatch.SKIPPED);
    for (FaceMatch outcome : FaceMatch.values()) {
      assertThat(new FakeBiometricVerifier(outcome).verify("employee", site, station)).isEqualTo(outcome);
    }
  }
}
