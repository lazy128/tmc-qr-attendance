package tmc.access.domain;

public record EventId(String value) {
  public EventId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("eventId required");
    }
  }
}
