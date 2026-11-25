package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.infrastructure.persistence.InMemoryEdgeStore;

class OutcomeRecordStepTest {
  private final InMemoryEdgeStore store = new InMemoryEdgeStore();
  private final AccessContext context = preparedContext();

  @BeforeEach
  void prepareOutcome() {
    store.persistPrepared(context.attempt, CLAIMS.jti());
    context.attempt = context.attempt.withCommand(Command.ACKED);
  }

  @Test
  void recordsFinalOutcomeForThePreparedAttempt() {
    var step = new OutcomeRecordStep(store);
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());
    assertThat(store.findAttempt(REQUEST.eventId().value())).contains(context.attempt);
  }

  @Test
  void succeedsWithoutAnAttemptAndDoesNotWrite() {
    var step = new OutcomeRecordStep(store);
    assertThat(step.execute(REQUEST, new AccessContext())).isEqualTo(StepResult.ok());
    assertThat(store.findAttempt(REQUEST.eventId().value()).orElseThrow().command()).isEqualTo(Command.SENT);
  }

  @Test
  void reportsFailureIfOutcomeCannotBeRecorded() {
    var missingAttemptStore = new InMemoryEdgeStore();
    var step = new OutcomeRecordStep(missingAttemptStore);
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.deny(Reason.PERSIST_FAILED));
    assertThat(missingAttemptStore.findAttempt(REQUEST.eventId().value())).isEmpty();
  }
}
