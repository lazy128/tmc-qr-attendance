package tmc.access.infrastructure.turnstile;

import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

class FakeTurnstileContractTest extends GateCommandSenderContractTest {
  @Override
  protected GateCommandSender sender() {
    return new FakeTurnstileAdapter(GateResult.ACKED);
  }

  @Override
  protected GateResult expected() {
    return GateResult.ACKED;
  }
}
