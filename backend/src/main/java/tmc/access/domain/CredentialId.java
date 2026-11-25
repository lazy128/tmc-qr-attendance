package tmc.access.domain;

public record CredentialId(String value) {
  public CredentialId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("credentialId required");
    }
  }
}
