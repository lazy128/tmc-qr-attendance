package tmc.access.domain;

import tmc.access.domain.AccessStates.Reason;

public final class CredentialChangedException extends RuntimeException {
  private final Reason reason;

  public CredentialChangedException(Reason reason) { this.reason = reason; }
  public Reason reason() { return reason; }
}
