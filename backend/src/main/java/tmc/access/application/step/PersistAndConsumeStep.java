package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AntiPassbackException;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.ReplayDetectedException;
import tmc.access.domain.port.AttemptStore;
import tmc.shared.clock.ClockProvider;

public final class PersistAndConsumeStep implements VerificationStep {
  private final AttemptStore attempts;
  private final ClockProvider clock;
  private final boolean sensorConfigured;

  public PersistAndConsumeStep(AttemptStore attempts, ClockProvider clock, boolean sensorConfigured) {
    this.attempts = attempts;
    this.clock = clock;
    this.sensorConfigured = sensorConfigured;
  }

  @Override
  public int order() {
    return 70;
  }

  @Override
  public String name() {
    return "PersistAndConsume";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    var claims = context.claims;
    var attempt = new AccessAttempt(
        request.eventId(),
        request.correlationId(),
        context.employeeId,
        request.siteId(),
        request.stationId(),
        request.direction(),
        request.scannedAt(),
        clock.now(),
        claims.credentialId(),
        claims.version(),
        claims.jti(),
        Decision.ALLOW,
        null,
        Command.SENT,
        sensorConfigured ? Passage.UNCONFIRMED : Passage.NOT_AVAILABLE,
        false,
        null,
        false,
        context.attendanceGate
    );
    try {
      attempts.persistPrepared(attempt, claims.jti());
      context.attempt = attempt;
      context.persisted = true;
      return StepResult.ok();
    } catch (ReplayDetectedException replay) {
      return StepResult.deny(Reason.REPLAY);
    } catch (AntiPassbackException denied) {
      return StepResult.deny(Reason.ANTI_PASSBACK);
    } catch (RuntimeException persistFailed) {
      return StepResult.deny(Reason.PERSIST_FAILED);
    }
  }
}
