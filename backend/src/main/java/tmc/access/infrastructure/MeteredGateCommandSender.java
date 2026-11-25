package tmc.access.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public final class MeteredGateCommandSender implements GateCommandSender {
  private final GateCommandSender delegate;
  private final Map<GateResult, Counter> outcomes = new EnumMap<>(GateResult.class);

  public MeteredGateCommandSender(GateCommandSender delegate, MeterRegistry registry) {
    this.delegate = delegate;
    for (GateResult result : GateResult.values()) {
      outcomes.put(result, Counter.builder("tmc.edge.gate.commands")
          .tag("status", result == GateResult.TIMEOUT ? "UNKNOWN" : result.name())
          .description("Initial outcome of each gate command sent by the verify flow")
          .register(registry));
    }
  }

  @Override
  public GateResult send(String commandId, AccessRequest request) {
    GateResult result = GateResult.TIMEOUT;
    try {
      result = delegate.send(commandId, request);
      return result;
    } finally {
      outcomes.get(result).increment();
    }
  }
}
