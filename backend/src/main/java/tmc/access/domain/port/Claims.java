package tmc.access.domain.port;

import java.time.Instant;
import tmc.access.domain.Direction;

public record Claims(
    String issuer,
    String audience,
    String credentialId,
    String jti,
    Instant issuedAt,
    Instant notBefore,
    Instant expiresAt,
    int version,
    String deviceHash,
    String keyId,
    String employeeId,
    Direction attendanceDirection
) {
  public Claims(String issuer, String audience, String credentialId, String jti, Instant issuedAt,
      Instant notBefore, Instant expiresAt, int version, String deviceHash, String keyId, String employeeId) {
    this(issuer, audience, credentialId, jti, issuedAt, notBefore, expiresAt,
        version, deviceHash, keyId, employeeId, null);
  }
}
