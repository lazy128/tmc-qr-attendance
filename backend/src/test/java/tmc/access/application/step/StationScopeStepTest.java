package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.infrastructure.security.EdgeSecuritySimulation;

class StationScopeStepTest {
  @ParameterizedTest
  @CsvSource({
      "GATE-1, SITE-1, SITE-1, true",
      "GATE-1, SITE-2, SITE-1, false",
      "UNKNOWN, SITE-1, , false",
      "UNKNOWN, SITE-2, , false"
  })
  void checksStationAndRequestedSite(String station, String site, String assignedSite, boolean allowed) {
    var context = new AccessContext();
    var step = new StationScopeStep(new EdgeSecuritySimulation("GATE-1", "SITE-1"));
    var scan = request(station, site, REQUEST.direction(), NOW, REQUEST.token(), DEVICE);
    assertThat(step.execute(scan, context))
        .isEqualTo(allowed ? StepResult.ok() : StepResult.deny(Reason.STATION_SCOPE));
    assertThat(context.stationSite).isEqualTo(assignedSite);
  }
}
