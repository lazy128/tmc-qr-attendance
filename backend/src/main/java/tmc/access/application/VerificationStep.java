package tmc.access.application;

import tmc.access.domain.AccessRequest;

public interface VerificationStep {
  int order();

  String name();

  StepResult execute(AccessRequest request, AccessContext context);
}
