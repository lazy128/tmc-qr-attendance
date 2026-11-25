package tmc.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tmc.access.application.step.GateCommandStep;
import tmc.access.application.step.OutcomeRecordStep;
import tmc.access.application.step.PersistAndConsumeStep;
import tmc.access.application.step.PolicyStep;
import tmc.access.application.step.TokenSignatureAndTimeStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.GateResult;
import tmc.access.infrastructure.InMemoryEdgeStore;
import tmc.access.infrastructure.MutableSnapshot;

class VerificationPipelineTest {
  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
  private static final AccessRequest REQUEST = new AccessRequest(
      new EventId("event"), "corr", new SiteId("SITE-1"), new StationId("GATE-1"),
      Direction.ENTRY, NOW, "test-token", "demo-device"
  );

  @Test
  void reportsTokenClockAndAuthorityErrors() {
    assertThat(tokenCheck(claims("tmc", "tmc-gates", NOW, NOW, NOW.plusSeconds(30))).passed()).isTrue();
    assertThat(tokenCheck(claims("tmc", "tmc-gates", NOW.minusSeconds(60), NOW.minusSeconds(60),
        NOW.minusSeconds(6))).reason()).isEqualTo(Reason.TOKEN_EXPIRED);
    assertThat(tokenCheck(claims("tmc", "tmc-gates", NOW, NOW.plusSeconds(6),
        NOW.plusSeconds(30))).reason()).isEqualTo(Reason.NOT_YET_VALID);
    assertThat(tokenCheck(claims("tmc", "tmc-gates", NOW.plusSeconds(6), NOW.plusSeconds(6),
        NOW.plusSeconds(30))).reason()).isEqualTo(Reason.CLOCK_SKEW);
    assertThat(tokenCheck(claims("wrong", "tmc-gates", NOW, NOW, NOW.plusSeconds(30))).reason())
        .isEqualTo(Reason.INVALID_TOKEN);
    assertThat(tokenCheck(claims("tmc", "wrong", NOW, NOW, NOW.plusSeconds(30))).reason())
        .isEqualTo(Reason.INVALID_TOKEN);
  }

  @Test
  void checksSnapshotFreshnessAndPolicy() {
    var stale = new PolicyStep((claims, request) -> true, new MutableSnapshot(NOW.minusSeconds(901)),
        () -> NOW, Duration.ofMinutes(15));
    var denied = new PolicyStep((claims, request) -> false, new MutableSnapshot(NOW),
        () -> NOW, Duration.ofMinutes(15));
    assertThat(stale.execute(REQUEST, new AccessContext()).reason()).isEqualTo(Reason.SNAPSHOT_STALE);
    assertThat(denied.execute(REQUEST, new AccessContext()).reason()).isEqualTo(Reason.POLICY_DENIED);
  }

  @Test
  void acceptsAnExtraStepAndReturnsInProgressWhileCommandIsPending() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var store = new InMemoryEdgeStore();
    var useCase = useCase(store, (command, request) -> {
      entered.countDown();
      try {
        if (!release.await(3, TimeUnit.SECONDS)) return GateResult.TIMEOUT;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return GateResult.TIMEOUT;
      }
      return GateResult.ACKED;
    });
    var original = CompletableFuture.supplyAsync(() -> useCase.execute(REQUEST));
    try {
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(useCase.execute(REQUEST).inProgress()).isTrue();
    } finally {
      release.countDown();
    }
    assertThat(original.get(3, TimeUnit.SECONDS).steps()).contains("ExtraStep:PASSED");
    assertThat(useCase.execute(REQUEST).attempt().command()).isEqualTo(Command.ACKED);
  }

  @ParameterizedTest
  @EnumSource(value = GateResult.class, names = {"TIMEOUT", "FAILED"})
  void aRepeatedRequestDoesNotSendTheGateCommandAgain(GateResult result) {
    var store = new InMemoryEdgeStore();
    var sends = new AtomicInteger();
    var useCase = useCase(store, (command, request) -> {
      sends.incrementAndGet();
      return result;
    });
    var initial = useCase.execute(REQUEST);
    assertThat(initial.attempt().command()).isEqualTo(result == GateResult.TIMEOUT ? Command.UNKNOWN : Command.FAILED);
    assertThat(useCase.execute(REQUEST).attempt().command()).isEqualTo(initial.attempt().command());
    assertThat(sends).hasValue(1);
  }

  private static StepResult tokenCheck(Claims claims) {
    return new TokenSignatureAndTimeStep(token -> claims, () -> NOW, Duration.ofSeconds(5), "tmc", "tmc-gates")
        .execute(REQUEST, new AccessContext());
  }

  private static Claims claims(String issuer, String audience, Instant iat, Instant nbf, Instant exp) {
    return new Claims(issuer, audience, "demo-credential", "jti", iat, nbf, exp, 1, "demo-device", "kid", null);
  }

  private static VerifyAccessUseCase useCase(InMemoryEdgeStore store, tmc.access.domain.port.GateCommandSender gate) {
    VerificationStep extra = new VerificationStep() {
      @Override public int order() { return 35; }
      @Override public String name() { return "ExtraStep"; }
      @Override public StepResult execute(AccessRequest request, AccessContext context) {
        context.claims = claims("tmc", "tmc-gates", NOW, NOW, NOW.plusSeconds(30));
        context.employeeId = "demo-employee";
        return StepResult.ok();
      }
    };
    return new VerifyAccessUseCase(
        List.of(new OutcomeRecordStep(store), new GateCommandStep(gate),
            new PersistAndConsumeStep(store, () -> NOW, false), extra), store, store, () -> NOW
    );
  }
}
