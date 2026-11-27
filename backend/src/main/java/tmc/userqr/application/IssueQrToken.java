package tmc.userqr.application;

import java.time.Duration;
import java.util.Objects;
import tmc.access.domain.Direction;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.TokenSigner;
import tmc.shared.clock.ClockProvider;
import tmc.shared.id.IdGenerator;

public final class IssueQrToken {
  private final CredentialReader reader;
  private final TokenSigner signer;
  private final ClockProvider clock;
  private final IdGenerator ids;
  private final Duration ttl;
  private final String issuer;
  private final String audience;
  private final String keyId;

  public IssueQrToken(
      CredentialReader reader,
      TokenSigner signer,
      ClockProvider clock,
      IdGenerator ids,
      Duration ttl,
      String issuer,
      String audience,
      String keyId
  ) {
    this.reader = reader;
    this.signer = signer;
    this.clock = clock;
    this.ids = ids;
    this.ttl = ttl;
    this.issuer = issuer;
    this.audience = audience;
    this.keyId = keyId;
  }

  public IssuedToken issue(String credentialId, String deviceHash) {
    return issue(credentialId, deviceHash, null);
  }

  public IssuedToken issue(String credentialId, String deviceHash, Direction attendanceDirection) {
    Credential credential = reader.getCredential(credentialId)
        .orElseThrow(() -> new IllegalArgumentException("credential not found"));
    if (!credential.active()) {
      throw new IllegalStateException("credential revoked");
    }
    if (!Objects.equals(credential.deviceHash(), deviceHash)) {
      throw new IllegalArgumentException("device binding does not match");
    }
    var now = clock.now();
    var expiresAt = now.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    String token = signer.sign(new Claims(
        issuer,
        audience,
        credential.id(),
        ids.next(),
        now,
        now,
        expiresAt,
        credential.version(),
        deviceHash,
        keyId,
        null,
        attendanceDirection
    ));
    return new IssuedToken(token, expiresAt);
  }

  public record IssuedToken(String token, java.time.Instant expiresAt) {}
}
