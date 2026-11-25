package tmc.access.domain.port;

import java.time.Instant;

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
    String employeeId
) {}
