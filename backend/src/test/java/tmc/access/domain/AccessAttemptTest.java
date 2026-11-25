package tmc.access.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Passage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessAttemptTest {
  @Test
  void commandTransitionsAreValidated() {
    var attempt = new AccessAttempt(
        new EventId("e"),
        "c",
        null,
        new SiteId("s"),
        new StationId("g"),
        Direction.ENTRY,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
        0,
        null,
        Decision.ALLOW,
        null,
        Command.NOT_SENT,
        Passage.UNCONFIRMED,
        false,
        null,
        false
    );
    assertThat(attempt.withCommand(Command.SENT).withCommand(Command.ACKED).command()).isEqualTo(Command.ACKED);
    assertThatThrownBy(() -> attempt.withCommand(Command.ACKED)).isInstanceOf(IllegalStateException.class);
  }
}
