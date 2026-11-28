package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.infrastructure.snapshot.MutableSnapshot;

class PolicyStepTest {
  private static final Duration MAX_AGE = Duration.ofMinutes(15);

  @Test
  void deniesStaleSnapshotBeforeEvaluatingPolicy() {
    var step = new PolicyStep((claims, scan) -> {
      throw new AssertionError("must check freshness before policy");
    }, new MutableSnapshot(NOW.minus(MAX_AGE).minusNanos(1)), () -> NOW, MAX_AGE);
    assertThat(step.execute(REQUEST, verifiedContext())).isEqualTo(StepResult.deny(Reason.SNAPSHOT_STALE));
  }

  @Test
  void futureSnapshotIsStaleWhenItExceedsFiveSecondsOfSkew() {
    var snapshot = new MutableSnapshot(NOW.plusSeconds(5));
    var step = new PolicyStep((claims, scan) -> true, snapshot, () -> NOW, MAX_AGE);
    assertThat(snapshot.remainingAt(NOW, MAX_AGE)).isEqualTo(MAX_AGE);
    assertThat(step.execute(REQUEST, verifiedContext())).isEqualTo(StepResult.ok());

    snapshot.refresh(NOW.plusSeconds(5).plusNanos(1));
    assertThat(snapshot.remainingAt(NOW, MAX_AGE).isNegative()).isTrue();
    assertThat(step.execute(REQUEST, verifiedContext())).isEqualTo(StepResult.deny(Reason.SNAPSHOT_STALE));
  }

  @ParameterizedTest
  @CsvSource({"true, true", "true, false", "false, true"})
  void requiresBothPoliciesAtTheFreshnessBoundary(boolean localAllows, boolean snapshotAllows) {
    var step = new PolicyStep((claims, scan) -> {
      assertThat(claims).isSameAs(CLAIMS);
      assertThat(scan).isSameAs(REQUEST);
      return localAllows;
    }, new PolicySnapshot(NOW.minus(MAX_AGE), snapshotAllows), () -> NOW, MAX_AGE);
    assertThat(step.execute(REQUEST, verifiedContext()))
        .isEqualTo(localAllows && snapshotAllows ? StepResult.ok() : StepResult.deny(Reason.POLICY_DENIED));
  }

  private record PolicySnapshot(Instant snapshotTime, boolean allows) implements SnapshotProvider {
    @Override
    public boolean revoked(String credentialId) {
      return false;
    }

    @Override
    public boolean policyAllows(Claims claims, AccessRequest request) {
      assertThat(claims).isSameAs(CLAIMS);
      assertThat(request).isSameAs(REQUEST);
      return allows;
    }
  }
}
