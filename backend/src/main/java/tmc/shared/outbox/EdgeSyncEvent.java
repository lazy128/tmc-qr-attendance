package tmc.shared.outbox;

import java.time.Instant;

/** Wire contract between the Edge outbox and the Central sync endpoint. */
public record EdgeSyncEvent(
    String eventId,
    String correlationId,
    String employeeId,
    String siteId,
    String stationId,
    String direction,
    Instant scannedAt,
    Instant receivedAt,
    String credentialId,
    int credentialVersion,
    String jti,
    String decision,
    String reasonCode,
    String command,
    String passage,
    String evidence,
    boolean offlineAccepted
) {}
