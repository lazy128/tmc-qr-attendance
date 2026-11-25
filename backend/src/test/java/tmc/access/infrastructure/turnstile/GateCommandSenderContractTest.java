package tmc.access.infrastructure.turnstile;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;
import static org.assertj.core.api.Assertions.assertThat;

abstract class GateCommandSenderContractTest {
  protected abstract GateCommandSender sender();

  protected abstract GateResult expected();

  @Test
  void repeatsSameCommandIdempotently() {
    var sender = sender();
    var request = request();
    assertThat(sender.send("cmd-1", request)).isEqualTo(expected());
    assertThat(sender.send("cmd-1", request)).isEqualTo(expected());
  }

  @Test
  void ackIsOnlyCommandEvidence() {
    var result = sender().send("cmd-ack", request());
    Command command = result == GateResult.ACKED
        ? Command.ACKED
        : result == GateResult.FAILED ? Command.FAILED : Command.UNKNOWN;
    var attempt = new AccessAttempt(
        new EventId("e"),
        "c",
        "emp",
        new SiteId("s"),
        new StationId("g"),
        Direction.ENTRY,
        Instant.EPOCH,
        Instant.EPOCH,
        "cred",
        1,
        "j",
        Decision.ALLOW,
        null,
        Command.SENT,
        Passage.UNCONFIRMED,
        false,
        null,
        false
    ).withCommand(command);
    assertThat(attempt.passage()).isEqualTo(Passage.UNCONFIRMED);
    assertThat(attempt.evidence()).isEqualTo(command == Command.ACKED ? Evidence.ACK_ONLY : null);
  }

  private AccessRequest request() {
    return new AccessRequest(
        new EventId("evt"),
        "corr",
        new SiteId("s"),
        new StationId("g"),
        Direction.ENTRY,
        Instant.EPOCH,
        "token",
        "device"
    );
  }
}
