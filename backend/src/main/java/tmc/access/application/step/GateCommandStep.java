package tmc.access.application.step;

import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Channel;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public final class GateCommandStep implements VerificationStep {
  private final GateCommandSender gate;

  public GateCommandStep(GateCommandSender gate) {
    this.gate = gate;
  }

  @Override
  public int order() {
    return 80;
  }

  @Override
  public String name() {
    return "GateCommand";
  }

  @Override
  public boolean applies(AccessRequest request) {
    return request.channel() != Channel.PERSONAL_SELF;
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    if (!context.persisted) {
      return StepResult.deny(Reason.PERSIST_FAILED);
    }
    context.gateInvoked = true;
    GateResult result;
    try {
      result = gate.send(context.attempt.eventId().value(), request);
    } catch (RuntimeException timeout) {
      result = GateResult.TIMEOUT;
    }
    Command command = switch (result) {
      case ACKED -> Command.ACKED;
      case FAILED -> Command.FAILED;
      case TIMEOUT -> Command.UNKNOWN;
    };
    context.attempt = context.attempt.withCommand(command);
    return StepResult.ok();
  }
}
