package tmc.access.domain.port;

import tmc.access.domain.AccessAttempt;

public interface OutboxPublisher {
  void publish(AccessAttempt attempt);
}
