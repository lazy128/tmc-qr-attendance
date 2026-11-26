package tmc.sync.application;

import tmc.access.domain.AccessAttempt;

@FunctionalInterface
public interface CentralAttendanceConsumer {
  void record(AccessAttempt attempt);
}
