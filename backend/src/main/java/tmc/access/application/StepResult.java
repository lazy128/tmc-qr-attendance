package tmc.access.application;

import tmc.access.domain.AccessStates.Reason;

public record StepResult(boolean passed, Reason reason) {
  public static StepResult ok() {
    return new StepResult(true, null);
  }

  public static StepResult deny(Reason reason) {
    return new StepResult(false, reason);
  }
}
