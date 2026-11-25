package tmc.access.domain;

public record StationId(String value) {
  public StationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("stationId required");
    }
  }
}
