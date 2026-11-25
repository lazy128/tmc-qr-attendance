package tmc.access.application.step;

import java.time.Instant;
import tmc.access.application.AccessContext;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.Claims;

final class StepFixtures {
  static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
  static final String EMPLOYEE = "employee-1";
  static final String CREDENTIAL = "credential-1";
  static final String DEVICE = "device-1";
  static final String KID = "key-1";
  static final String ISSUER = "tmc";
  static final String AUDIENCE = "tmc-gates";
  static final AccessRequest REQUEST = request("GATE-1", "SITE-1", Direction.ENTRY, NOW, "test-token", DEVICE);
  static final Claims CLAIMS = claims(ISSUER, AUDIENCE, NOW, NOW, NOW.plusSeconds(30), KID);

  private StepFixtures() {}

  static AccessRequest request(
      String station, String site, Direction direction, Instant scannedAt, String token, String device
  ) {
    return new AccessRequest(new EventId("event-1"), "correlation-1", new SiteId(site), new StationId(station),
        direction, scannedAt, token, device);
  }

  static Claims claims(String issuer, String audience, Instant issued, Instant notBefore, Instant expires, String kid) {
    return new Claims(issuer, audience, CREDENTIAL, "jti-1", issued, notBefore, expires, 3, DEVICE, kid, null);
  }

  static AccessContext verifiedContext() {
    var context = new AccessContext();
    context.claims = CLAIMS;
    context.employeeId = EMPLOYEE;
    return context;
  }

  static AccessContext preparedContext() {
    var context = verifiedContext();
    context.attempt = new AccessAttempt(REQUEST.eventId(), REQUEST.correlationId(), EMPLOYEE, REQUEST.siteId(),
        REQUEST.stationId(), REQUEST.direction(), NOW, NOW, CREDENTIAL, CLAIMS.version(), CLAIMS.jti(),
        Decision.ALLOW, null, Command.SENT, Passage.NOT_AVAILABLE, false, null, false);
    context.persisted = true;
    return context;
  }
}
