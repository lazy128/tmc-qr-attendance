package tmc.sync.application;

import java.util.function.Supplier;
import tmc.access.domain.AccessAttempt;

public interface CentralEventStore {
  <T> T locked(Supplier<T> work);
  AccessAttempt find(String eventId);
  String firstSite(String jti);
  void save(AccessAttempt attempt, boolean newEvent, boolean newToken);
}
