package tmc.access.infrastructure;

import java.util.concurrent.ConcurrentHashMap;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.CommandStatusReader;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public class FakeTurnstileAdapter implements GateCommandSender, CommandStatusReader {
  private final GateResult configured;
  private final ConcurrentHashMap<String, GateResult> commands = new ConcurrentHashMap<>();

  public FakeTurnstileAdapter(GateResult configured) {
    this.configured = configured;
  }

  @Override
  public GateResult send(String commandId, AccessRequest request) {
    return commands.computeIfAbsent(commandId, key -> configured);
  }

  public int commandCount() {
    return commands.size();
  }

  @Override
  public GateResult status(String commandId) {
    return commands.getOrDefault(commandId, GateResult.TIMEOUT);
  }
}
