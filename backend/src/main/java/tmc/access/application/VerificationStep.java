package tmc.access.application;

import tmc.access.domain.AccessRequest;

public interface VerificationStep {
  int order();

  String name();

  default boolean applies(AccessRequest request) {
    return true;
  }

  StepResult execute(AccessRequest request, AccessContext context);
}
