package tmc.access.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

class EdgeOutboxCodecTest {
  @Test
  void encodesExistingPendingRowsWithTheSameBytes() {
    var scannedAt = Instant.parse("2026-11-25T10:00:00.123456Z");
    var attempt = new AccessAttempt(
        new EventId("e1"), "trace-1", "employee-1", new SiteId("SITE-1"), new StationId("GATE-1"),
        Direction.ENTRY, scannedAt, scannedAt.plusNanos(1000), "credential-1", 2, "jti-1",
        Decision.ALLOW, null, Command.ACKED, Passage.NOT_AVAILABLE, false, Evidence.ACK_ONLY, false
    );

    String existingPayload = "{\"eventId\":\"e1\",\"correlationId\":\"trace-1\",\"employeeId\":\"employee-1\","
        + "\"siteId\":\"SITE-1\",\"stationId\":\"GATE-1\",\"direction\":\"ENTRY\","
        + "\"scannedAt\":\"2026-11-25T10:00:00.123456Z\",\"receivedAt\":\"2026-11-25T10:00:00.123457Z\","
        + "\"credentialId\":\"credential-1\",\"credentialVersion\":2,\"jti\":\"jti-1\",\"decision\":\"ALLOW\","
        + "\"reasonCode\":null,\"command\":\"ACKED\",\"passage\":\"NOT_AVAILABLE\",\"evidence\":\"ACK_ONLY\","
        + "\"offlineAccepted\":false}";
    assertThat(new EdgeOutboxCodec().encode(attempt)).isEqualTo(existingPayload);
  }
}
