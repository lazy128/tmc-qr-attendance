package tmc.attendance;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Evidence;
import tmc.attendance.application.DefaultEligibilityPolicy;
import tmc.attendance.application.RecordAttendance;
import tmc.attendance.contract.AttendanceEvent;
import tmc.attendance.infrastructure.InMemoryAttendanceStore;
import static org.assertj.core.api.Assertions.assertThat;

class AttendanceEligibilityTest {
  private AttendanceEvent event(Command command, Evidence evidence) {
    return new AttendanceEvent(
        "e" + command,
        "emp",
        "site",
        "gate",
        "ENTRY",
        Instant.EPOCH,
        command.name(),
        "UNCONFIRMED",
        evidence == null ? null : evidence.name(),
        false
    );
  }

  @Test
  void onlyAckedEligibleAndAckOnlyNeedsReview() {
    var store = new InMemoryAttendanceStore();
    var recorder = new RecordAttendance(store, new DefaultEligibilityPolicy());
    recorder.accept(event(Command.ACKED, Evidence.ACK_ONLY));
    assertThat(store.find("eACKED").orElseThrow().eligible()).isTrue();
    assertThat(store.find("eACKED").orElseThrow().hrReview()).isTrue();
    recorder.accept(event(Command.UNKNOWN, null));
    assertThat(store.find("eUNKNOWN").orElseThrow().eligible()).isFalse();
    recorder.accept(event(Command.FAILED, null));
    assertThat(store.find("eFAILED").orElseThrow().eligible()).isFalse();
  }
}
