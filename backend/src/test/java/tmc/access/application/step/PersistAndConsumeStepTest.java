package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.AntiPassbackException;
import tmc.access.domain.ReplayDetectedException;
import tmc.access.domain.port.AttemptStore;
import tmc.access.infrastructure.persistence.InMemoryEdgeStore;

class PersistAndConsumeStepTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void storesPreparedAttemptAndConsumesJti(boolean sensorConfigured) {
    var store = new InMemoryEdgeStore();
    var context = verifiedContext();
    var step = new PersistAndConsumeStep(store, () -> NOW.plusSeconds(1), sensorConfigured);
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());

    var attempt = context.attempt;
    assertThat(context.persisted).isTrue();
    assertThat(attempt.eventId()).isEqualTo(REQUEST.eventId());
    assertThat(attempt.employeeId()).isEqualTo(EMPLOYEE);
    assertThat(attempt.credentialId()).isEqualTo(CREDENTIAL);
    assertThat(attempt.jti()).isEqualTo(CLAIMS.jti());
    assertThat(attempt.decision()).isEqualTo(Decision.ALLOW);
    assertThat(attempt.command()).isEqualTo(Command.SENT);
    assertThat(attempt.passage()).isEqualTo(sensorConfigured ? Passage.UNCONFIRMED : Passage.NOT_AVAILABLE);
    assertThat(store.findAttempt(REQUEST.eventId().value())).contains(attempt);
    assertThat(store.outbox()).containsExactly(attempt);
    assertThat(store.alreadyConsumed(REQUEST.siteId().value(), CLAIMS.jti())).isTrue();
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("writeFailures")
  void mapsWriteFailureWithoutMarkingAttemptPersisted(RuntimeException error, Reason reason) {
    var context = verifiedContext();
    var step = new PersistAndConsumeStep(new FailingAttemptStore(error), () -> NOW, false);
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.deny(reason));
    assertThat(context.persisted).isFalse();
    assertThat(context.attempt).isNull();
  }

  static Stream<Arguments> writeFailures() {
    return Stream.of(
        Arguments.of(new ReplayDetectedException(), Reason.REPLAY),
        Arguments.of(new AntiPassbackException(), Reason.ANTI_PASSBACK),
        Arguments.of(new IllegalStateException("store unavailable"), Reason.PERSIST_FAILED)
    );
  }

  private record FailingAttemptStore(RuntimeException error) implements AttemptStore {
    @Override
    public void persistPrepared(AccessAttempt attempt, String jti) {
      throw error;
    }

    @Override
    public void persistDenied(AccessAttempt attempt) {
      throw new AssertionError("unexpected denial write");
    }

    @Override
    public void update(AccessAttempt attempt) {
      throw new AssertionError("unexpected attempt update");
    }

    @Override
    public Optional<AccessAttempt> findAttempt(String eventId) {
      throw new AssertionError("unexpected attempt lookup");
    }
  }
}
