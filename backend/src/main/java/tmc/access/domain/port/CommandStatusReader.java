package tmc.access.domain.port;

public interface CommandStatusReader {
  GateResult status(String commandId);
}
