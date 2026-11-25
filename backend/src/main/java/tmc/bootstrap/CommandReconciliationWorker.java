package tmc.bootstrap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.port.CommandStatusReader;
import tmc.access.domain.port.GateResult;
import tmc.access.infrastructure.JdbcAttemptStore;

@Component
public class CommandReconciliationWorker {
  private final JdbcAttemptStore attempts;
  private final CommandStatusReader status;

  public CommandReconciliationWorker(
      JdbcAttemptStore attempts,
      CommandStatusReader status
  ) {
    this.attempts = attempts;
    this.status = status;
  }

  @Scheduled(fixedDelay = 2000)
  public void reconcile() {
    for (var attempt : attempts.unknownCommands(50)) {
      var state = status.status(attempt.eventId().value());
      var command = state == GateResult.ACKED ? Command.ACKED
          : state == GateResult.FAILED ? Command.FAILED
          : attempt.command() == Command.SENT ? Command.UNKNOWN : null;
      if (command == null || command == attempt.command()) continue;
      var next = attempt.withCommand(command);
      attempts.recordOutcome(next);
    }
  }
}
