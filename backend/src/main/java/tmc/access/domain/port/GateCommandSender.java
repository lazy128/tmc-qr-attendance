package tmc.access.domain.port;

import tmc.access.domain.AccessRequest;

public interface GateCommandSender {
  GateResult send(String commandId, AccessRequest request);
}
