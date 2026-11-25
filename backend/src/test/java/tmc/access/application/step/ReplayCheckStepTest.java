package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import org.junit.jupiter.api.Test;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.infrastructure.persistence.InMemoryEdgeStore;

class ReplayCheckStepTest {
  @Test
  void rejectsOnlyTheJtiAlreadyConsumedAtTheRequestedSite() {
    var store = new InMemoryEdgeStore();
    var step = new ReplayCheckStep(store);
    var context = verifiedContext();
    store.consume("SITE-2", CLAIMS.jti(), "other-event");
    store.consume("SITE-1", "other-jti", "other-event");
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());
    assertThat(store.alreadyConsumed("SITE-1", CLAIMS.jti())).isFalse();

    store.consume("SITE-1", CLAIMS.jti(), "previous-event");
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.deny(Reason.REPLAY));
  }
}
