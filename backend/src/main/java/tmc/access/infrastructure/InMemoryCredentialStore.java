package tmc.access.infrastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.CredentialWriter;

public final class InMemoryCredentialStore implements CredentialReader, CredentialWriter {
  private final Map<String, Credential> credentials = new HashMap<>();

  @Override
  public synchronized Optional<Credential> getCredential(String id) {
    return Optional.ofNullable(credentials.get(id));
  }

  @Override
  public synchronized void save(Credential credential) {
    credentials.put(credential.id(), credential);
  }
}
