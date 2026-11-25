package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.REQUEST;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;

class StationAuthStepTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void usesAuthenticationResultForTheRequest(boolean authenticated) {
    var step = new StationAuthStep(request -> {
      assertThat(request).isSameAs(REQUEST);
      return authenticated;
    });
    assertThat(step.execute(REQUEST, new AccessContext()))
        .isEqualTo(authenticated ? StepResult.ok() : StepResult.deny(Reason.STATION_AUTH_FAILED));
  }
}
