package tmc.access.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Channel;
import tmc.shared.outbox.EdgeSyncEvent;

final class EdgeOutboxCodec {
  private final ObjectMapper json = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  String encode(AccessAttempt attempt) {
    var event = new EdgeSyncEvent(
        attempt.eventId().value(), attempt.correlationId(), attempt.employeeId(), attempt.siteId().value(),
        attempt.stationId().value(), attempt.direction().name(), attempt.scannedAt(), attempt.receivedAt(),
        attempt.credentialId(), attempt.credentialVersion(), attempt.jti(), attempt.decision().name(),
        enumName(attempt.reasonCode()), attempt.command().name(), attempt.passage().name(),
        enumName(attempt.evidence()), attempt.offlineAccepted(), attempt.attendanceGate() ? null : true,
        attempt.channel() == Channel.GATE_SCAN ? null : attempt.channel().name()
    );
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Outbox serialization failed", error);
    }
  }

  String eventId(String payload) {
    try {
      return json.readTree(payload).path("eventId").asText();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Invalid outbox payload", error);
    }
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
