package tmc.shared.outbox;

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
import static org.assertj.core.api.Assertions.assertThat;

class CentralSyncReceiverTest {
  private AccessAttempt attempt(String eventId, String siteId) {
    return new AccessAttempt(
        new EventId(eventId),
        "c",
        "emp",
        new SiteId(siteId),
        new StationId("g"),
        Direction.ENTRY,
        Instant.EPOCH,
        Instant.EPOCH,
        "cred",
        1,
        "shared-jti",
        Decision.ALLOW,
        null,
        Command.ACKED,
        Passage.UNCONFIRMED,
        false,
        Evidence.ACK_ONLY,
        false
    );
  }

  @Test
  void duplicateEventIsIdempotentAndCrossSiteJtiSuspicious() {
    var receiver = new CentralSyncReceiver();
    assertThat(receiver.receive(attempt("e1", "s1")).suspicious()).isFalse();
    assertThat(receiver.receive(attempt("e1", "s1")).duplicate()).isTrue();
    assertThat(receiver.receive(attempt("e2", "s2")).suspicious()).isTrue();
  }
}
