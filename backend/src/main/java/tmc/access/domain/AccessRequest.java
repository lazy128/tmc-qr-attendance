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
    String deviceId
) {}
