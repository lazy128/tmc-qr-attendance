package tmc.access.infrastructure.turnstile;

import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public final class SimulatorTurnstileAdapter implements GateCommandSender {
  private final GateResult result;

  public SimulatorTurnstileAdapter(GateResult result) {
    this.result = result;
  }

  @Override
  public GateResult send(String commandId, AccessRequest request) {
    return result;
  }
}
