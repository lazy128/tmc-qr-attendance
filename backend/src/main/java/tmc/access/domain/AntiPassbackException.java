package tmc.access.domain;

import tmc.access.domain.AccessStates.Reason;

public final class AntiPassbackException extends RuntimeException {
  private final Reason reason;

  public AntiPassbackException() { this(Reason.ANTI_PASSBACK); }

  public AntiPassbackException(Reason reason) {
    this.reason = reason;
  }

  public Reason reason() { return reason; }
}
