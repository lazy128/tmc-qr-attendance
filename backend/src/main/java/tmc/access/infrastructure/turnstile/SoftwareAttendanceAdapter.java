package tmc.access.infrastructure.turnstile;

import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.CommandStatusReader;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

/** Acknowledges a scan recorded by Edge. No physical turnstile is attached. */
public final class SoftwareAttendanceAdapter implements GateCommandSender, CommandStatusReader {
  @Override public GateResult send(String commandId, AccessRequest request) { return GateResult.ACKED; }

  // Reconciliation calls this only for commands with an already committed access attempt.
  @Override public GateResult status(String commandId) { return GateResult.ACKED; }
}
