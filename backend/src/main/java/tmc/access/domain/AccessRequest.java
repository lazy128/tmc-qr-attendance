package tmc.access.domain;

import java.time.Instant;

public record AccessRequest(
    EventId eventId,
    String correlationId,
    SiteId siteId,
    StationId stationId,
    Direction direction,
    Instant scannedAt,
    String token,
    String deviceId,
    Instant submittedAt,
    String stationProofId
) {
  public AccessRequest(EventId eventId, String correlationId, SiteId siteId, StationId stationId,
      Direction direction, Instant scannedAt, String token, String deviceId, Instant submittedAt) {
    this(eventId, correlationId, siteId, stationId, direction, scannedAt, token, deviceId, submittedAt, null);
  }

  public AccessRequest(EventId eventId, String correlationId, SiteId siteId, StationId stationId,
      Direction direction, Instant scannedAt, String token, String deviceId) {
    this(eventId, correlationId, siteId, stationId, direction, scannedAt, token, deviceId, scannedAt);
  }
}
