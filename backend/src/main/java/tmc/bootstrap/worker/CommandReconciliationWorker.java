package tmc.bootstrap.worker;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.port.CommandStatusReader;
import tmc.access.domain.port.GateResult;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.shared.clock.ClockProvider;

@Component
public class CommandReconciliationWorker {
  private static final Logger LOG = LoggerFactory.getLogger(CommandReconciliationWorker.class);
  private final JdbcAttemptStore attempts;
  private final CommandStatusReader status;
  private final ClockProvider clock;
  private final Duration deadline;

  public CommandReconciliationWorker(JdbcAttemptStore attempts, CommandStatusReader status, ClockProvider clock,
      @Value("${tmc.turnstile.reconciliation-seconds:120}") long seconds) {
    if (seconds < 3) throw new IllegalArgumentException("Gate reconciliation must allow at least 3 seconds");
    this.attempts = attempts;
    this.status = status;
    this.clock = clock;
    this.deadline = Duration.ofSeconds(seconds);
  }

  @Scheduled(fixedDelay = 2000)
  public void reconcile() {
    var now = clock.now();
    for (var attempt : attempts.pendingCommands(now.minusSeconds(2), 50)) {
      try {
        if (!attempt.receivedAt().plus(deadline).isAfter(now)) {
          attempts.expireCommand(attempt, now);
          continue;
        }
        GateResult state = status.status(attempt.eventId().value());
        Command command = state == GateResult.ACKED ? Command.ACKED
            : state == GateResult.FAILED ? Command.FAILED : Command.UNKNOWN;
        if (command != attempt.command()) attempts.recordOutcome(attempt.withCommand(command));
      } catch (RuntimeException unavailable) {
        LOG.warn("Cannot reconcile gate event {} ({})", attempt.eventId().value(), unavailable.getClass().getSimpleName());
      }
    }
  }
}
