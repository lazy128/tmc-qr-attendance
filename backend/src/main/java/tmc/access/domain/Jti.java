package tmc.access.domain;

public record Jti(String value) {
  public Jti {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("jti required");
    }
  }
}
