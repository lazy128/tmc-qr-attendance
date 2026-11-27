package tmc.access.domain;

import java.time.Instant;
import tmc.access.domain.AccessStates.Channel;

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
    String stationProofId,
    Channel channel
) {
  public AccessRequest(EventId eventId, String correlationId, SiteId siteId, StationId stationId,
      Direction direction, Instant scannedAt, String token, String deviceId, Instant submittedAt) {
    this(eventId, correlationId, siteId, stationId, direction, scannedAt, token, deviceId, submittedAt, null, Channel.GATE_SCAN);
  }

  public AccessRequest(EventId eventId, String correlationId, SiteId siteId, StationId stationId,
      Direction direction, Instant scannedAt, String token, String deviceId) {
    this(eventId, correlationId, siteId, stationId, direction, scannedAt, token, deviceId, scannedAt);
  }
}
