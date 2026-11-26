package tmc.shared;

import java.util.Optional;

public interface Identity {
  Optional<String> authenticate(String authorization);

  default Optional<String> assignedStation(String actor) { return Optional.empty(); }
}
