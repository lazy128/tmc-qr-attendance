package tmc.access.application.step;

import java.util.Objects;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.SnapshotProvider;

public final class CredentialStatusAndVersionStep implements VerificationStep {
  private final CredentialReader credentials;
  private final SnapshotProvider snapshot;

  public CredentialStatusAndVersionStep(CredentialReader credentials, SnapshotProvider snapshot) {
    this.credentials = credentials;
    this.snapshot = snapshot;
  }

  @Override
  public int order() {
    return 40;
  }

  @Override
  public String name() {
    return "CredentialStatusAndVersion";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    var claim = context.claims;
    if (claim == null) {
      return StepResult.deny(Reason.REVOKED);
    }
    var credential = credentials.getCredential(claim.credentialId());
    if (credential.isEmpty() || !credential.get().active() || snapshot.revoked(claim.credentialId())) {
      return StepResult.deny(Reason.REVOKED);
    }
    var current = credential.get();
    if (current.version() != claim.version()) {
      return StepResult.deny(Reason.CVER_MISMATCH);
    }
    if (!Objects.equals(current.deviceHash(), claim.deviceHash())
        || !Objects.equals(claim.deviceHash(), request.deviceId())) {
      return StepResult.deny(Reason.DEVICE_MISMATCH);
    }
    context.employeeId = current.employeeId();
    return StepResult.ok();
  }
}
