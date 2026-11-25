package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.GateResult;

class GateCommandStepTest {
  @Test
  void doesNotSendBeforePersistence() {
    var step = new GateCommandStep((id, scan) -> {
      throw new AssertionError("must not send a command without a persisted attempt");
    });
    var context = verifiedContext();
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.deny(Reason.PERSIST_FAILED));
    assertThat(context.gateInvoked).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"ACKED, ACKED", "FAILED, FAILED", "TIMEOUT, UNKNOWN"})
  void mapsGateOutcome(GateResult result, Command expected) {
    var sends = new AtomicInteger();
    var context = preparedContext();
    var step = new GateCommandStep((id, scan) -> {
      assertThat(id).isEqualTo(REQUEST.eventId().value());
      assertThat(scan).isSameAs(REQUEST);
      sends.incrementAndGet();
      return result;
    });
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());
    assertThat(sends).hasValue(1);
    assertThat(context.gateInvoked).isTrue();
    assertThat(context.attempt.command()).isEqualTo(expected);
  }

  @Test
  void recordsUnknownWhenAdapterThrows() {
    var context = preparedContext();
    var step = new GateCommandStep((id, scan) -> {
      throw new IllegalStateException("gate unavailable");
    });
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());
    assertThat(context.gateInvoked).isTrue();
    assertThat(context.attempt.command()).isEqualTo(Command.UNKNOWN);
  }
}
