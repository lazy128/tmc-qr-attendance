package tmc.access.domain.port;

import java.util.Optional;

public interface CredentialReader {
  Optional<Credential> getCredential(String id);
}
