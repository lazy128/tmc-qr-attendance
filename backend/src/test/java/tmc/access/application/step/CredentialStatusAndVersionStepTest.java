package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.Credential;
import tmc.access.infrastructure.snapshot.MutableSnapshot;

class CredentialStatusAndVersionStepTest {
  private static final Credential ACTIVE = new Credential(CREDENTIAL, EMPLOYEE, CLAIMS.version(), true, DEVICE);

  @Test
  void assignsEmployeeFromCurrentCredential() {
    var context = new AccessContext();
    context.claims = CLAIMS;
    var step = new CredentialStatusAndVersionStep(
        id -> CREDENTIAL.equals(id) ? Optional.of(ACTIVE) : Optional.empty(), new MutableSnapshot(NOW));
    assertThat(step.execute(REQUEST, context)).isEqualTo(StepResult.ok());
    assertThat(context.employeeId).isEqualTo(EMPLOYEE);
  }

  @Test
  void rejectsMissingClaimsBeforeReadingCredential() {
    var step = new CredentialStatusAndVersionStep(id -> {
      throw new AssertionError("credential must not be read without claims");
    }, new MutableSnapshot(NOW));
    assertThat(step.execute(REQUEST, new AccessContext())).isEqualTo(StepResult.deny(Reason.REVOKED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("denials")
  void rejectsCredentialWithoutAssigningEmployee(
      String scenario, Credential credential, boolean revoked, String scanDevice, Reason reason
  ) {
    var snapshot = new MutableSnapshot(NOW);
    if (revoked) snapshot.revoke(CREDENTIAL);
    var context = new AccessContext();
    context.claims = CLAIMS;
    var step = new CredentialStatusAndVersionStep(id -> Optional.ofNullable(credential), snapshot);
    var request = request("GATE-1", "SITE-1", REQUEST.direction(), NOW, REQUEST.token(), scanDevice);
    assertThat(step.execute(request, context)).isEqualTo(StepResult.deny(reason));
    assertThat(context.employeeId).isNull();
  }

  static Stream<Arguments> denials() {
    return Stream.of(
        Arguments.of("unknown credential", null, false, DEVICE, Reason.REVOKED),
        Arguments.of("inactive credential", new Credential(CREDENTIAL, EMPLOYEE, CLAIMS.version(), false, DEVICE),
            false, DEVICE, Reason.REVOKED),
        Arguments.of("snapshot revocation", ACTIVE, true, DEVICE, Reason.REVOKED),
        Arguments.of("different credential version",
            new Credential(CREDENTIAL, EMPLOYEE, CLAIMS.version() + 1, true, DEVICE),
            false, DEVICE, Reason.CVER_MISMATCH),
        Arguments.of("different current device binding",
            new Credential(CREDENTIAL, EMPLOYEE, CLAIMS.version(), true, "new-device"),
            false, DEVICE, Reason.DEVICE_MISMATCH),
        Arguments.of("different scanned device", ACTIVE, false, "other-device", Reason.DEVICE_MISMATCH)
    );
  }
}
