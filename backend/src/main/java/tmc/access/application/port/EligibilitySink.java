package tmc.access.application.port;

import tmc.access.domain.AccessAttempt;

public interface EligibilitySink {
  void accept(AccessAttempt attempt);
}
