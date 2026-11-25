package tmc.access.infrastructure;

import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

/** Integration placeholder. No gate request is sent before a vendor contract is available. */
public final class HttpTurnstileAdapter implements GateCommandSender {
  @Override
  public GateResult send(String commandId, AccessRequest request) {
    return GateResult.FAILED;
  }
}
