package tmc.access.application;

import tmc.access.domain.AccessAttempt;
import tmc.access.domain.port.Claims;

public final class AccessContext {
  public boolean idempotencyReserved;
  public String stationSite;
  public Claims claims;
  public AccessAttempt attempt;
  public String commandId;
  public boolean gateInvoked;
  public boolean persisted;
  public String employeeId;
}
