package tmc.access.infrastructure.turnstile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.GateResult;

class TurnstileCircuitTest {
  @Test
  void isolatesStationsAndRecoversAfterAnAcknowledgedProbe() {
    var failing = new AtomicBoolean(true);
    var bdCalls = new AtomicInteger();
    var time = new AtomicLong();
    try (var sender = new TimeoutGateCommandSender((id, request) -> {
      if (!"BD-G01".equals(request.stationId().value())) return GateResult.ACKED;
      bdCalls.incrementAndGet();
      return failing.get() ? GateResult.TIMEOUT : GateResult.ACKED;
    }, 1000, 2, Duration.ofSeconds(15), time::get)) {
      var bd = scan("SITE-BD", "BD-G01");
      assertThat(sender.send("one", bd)).isEqualTo(GateResult.TIMEOUT);
      assertThat(sender.send("two", bd)).isEqualTo(GateResult.TIMEOUT);
      assertThat(sender.send("blocked", bd)).isEqualTo(GateResult.FAILED);
      assertThat(bdCalls).hasValue(2);
      assertThat(sender.send("other-station", scan("SITE-LA", "LA-G01"))).isEqualTo(GateResult.ACKED);

      time.addAndGet(Duration.ofSeconds(15).toNanos());
      assertThat(sender.send("failed-probe", bd)).isEqualTo(GateResult.TIMEOUT);
      assertThat(sender.send("blocked-again", bd)).isEqualTo(GateResult.FAILED);
      assertThat(bdCalls).hasValue(3);
      time.addAndGet(Duration.ofSeconds(15).toNanos());
      failing.set(false);
      assertThat(sender.send("successful-probe", bd)).isEqualTo(GateResult.ACKED);
      assertThat(sender.send("normal-after-recovery", bd)).isEqualTo(GateResult.ACKED);
      assertThat(bdCalls).hasValue(5);
    }
  }

  private AccessRequest scan(String site, String station) {
    return new AccessRequest(new EventId("event"), "trace", new SiteId(site), new StationId(station),
        Direction.ENTRY, Instant.EPOCH, "test-token", "test-device");
  }
}
