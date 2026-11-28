package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.port.UsedTokenStore;
import tmc.access.infrastructure.persistence.InMemoryPresenceTracker;

class AntiPassbackStepTest {
  @Test
  void detectsReplayCommittedBetweenReplayCheckAndPresenceCheck() {
    var tokens = tokens();
    var context = verifiedContext();
    var presence = new InMemoryPresenceTracker();
    assertThat(new ReplayCheckStep(tokens).execute(REQUEST, context)).isEqualTo(StepResult.ok());
    tokens.consume("SITE-1", CLAIMS.jti(), "winner");
    presence.acknowledged("SITE-1", EMPLOYEE, Direction.ENTRY, "winner");
    assertThat(new AntiPassbackStep(presence, tokens).execute(REQUEST, context))
        .isEqualTo(StepResult.deny(Reason.REPLAY));
  }

  private static UsedTokenStore tokens() {
    return new UsedTokenStore() {
      private final AtomicBoolean consumed = new AtomicBoolean();
      @Override public boolean consume(String site, String jti, String event) {
        return consumed.compareAndSet(false, true);
      }
      @Override public boolean alreadyConsumed(String site, String jti) {
        return consumed.get();
      }
    };
  }

  @ParameterizedTest
  @CsvSource({
      "ENTRY, ENTRY, false",
      "EXIT, EXIT, false",
      "ENTRY, EXIT, true",
      "EXIT, ENTRY, true"
  })
  void checksDirectionForTheEmployeeAtTheRequestedSite(Direction previous, Direction next, boolean allowed) {
    var presence = new InMemoryPresenceTracker();
    presence.acknowledged("SITE-1", EMPLOYEE, previous, "previous-event");
    var step = new AntiPassbackStep(presence, tokens());
    var context = verifiedContext();
    var sameSite = request("GATE-1", "SITE-1", next, NOW, REQUEST.token(), DEVICE);
    var otherSite = request("GATE-2", "SITE-2", next, NOW, REQUEST.token(), DEVICE);
    assertThat(step.execute(sameSite, context))
        .isEqualTo(allowed ? StepResult.ok() : StepResult.deny(Reason.ANTI_PASSBACK));
    assertThat(step.execute(otherSite, context)).isEqualTo(StepResult.ok());
    context.employeeId = "other-employee";
    assertThat(step.execute(sameSite, context)).isEqualTo(StepResult.ok());
  }
}
