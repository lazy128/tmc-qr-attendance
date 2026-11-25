package tmc.access.infrastructure;

import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

class HttpTurnstileContractTest extends GateCommandSenderContractTest {
  @Override protected GateCommandSender sender() { return new HttpTurnstileAdapter(); }
  @Override protected GateResult expected() { return GateResult.FAILED; }
}
