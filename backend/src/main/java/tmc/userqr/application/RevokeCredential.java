package tmc.userqr.application;

import tmc.access.domain.port.Credential;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.CredentialWriter;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;

public final class RevokeCredential {
  private final CredentialReader reader;
  private final CredentialWriter writer;
  private final AuditWriter audit;
  private final ClockProvider clock;

  public RevokeCredential(
      CredentialReader reader,
      CredentialWriter writer,
      AuditWriter audit,
      ClockProvider clock
  ) {
    this.reader = reader;
    this.writer = writer;
    this.audit = audit;
    this.clock = clock;
  }

  public void revoke(String id, String actor, String correlation) {
    var current = reader.getCredential(id).orElseThrow(() -> new IllegalArgumentException("credential not found"));
    if (!current.active()) return;
    writer.save(new Credential(
        current.id(),
        current.employeeId(),
        current.version() + 1,
        false,
        current.deviceHash()
    ));
    audit.append(actor, "QR_REVOKE", id, "SUCCESS", correlation, clock.now());
  }
}
