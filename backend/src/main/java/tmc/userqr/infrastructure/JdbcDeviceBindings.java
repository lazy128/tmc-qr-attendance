package tmc.userqr.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tmc.access.domain.port.Credential;
import tmc.shared.DatabaseDialect;

@Repository
public class JdbcDeviceBindings {
  private final JdbcTemplate jdbc;
  private final boolean postgres;
  private final SecureRandom random = new SecureRandom();

  public JdbcDeviceBindings(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.postgres = DatabaseDialect.isPostgres(jdbc);
  }

  public String enroll(Credential credential) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(postgres ? """
        INSERT INTO device_binding (credential_id, device_hash, proof_hash) VALUES (?, ?, ?)
        ON CONFLICT (credential_id) DO UPDATE SET device_hash=EXCLUDED.device_hash, proof_hash=EXCLUDED.proof_hash
        """ : """
        MERGE INTO device_binding (credential_id, device_hash, proof_hash) KEY (credential_id)
        VALUES (?, ?, ?)
        """, credential.id(), credential.deviceHash(), hash(proof));
    return proof;
  }

  public Optional<String> deviceHash(String credentialId, String proof) {
    if (proof == null || proof.length() != 43) return Optional.empty();
    byte[] supplied = hash(proof).getBytes(StandardCharsets.US_ASCII);
    return jdbc.query("SELECT device_hash, proof_hash FROM device_binding WHERE credential_id = ?", row -> {
      if (!row.next() || !MessageDigest.isEqual(row.getString("proof_hash").getBytes(StandardCharsets.US_ASCII), supplied)) {
        return Optional.empty();
      }
      return Optional.of(row.getString("device_hash"));
    }, credentialId);
  }

  public Optional<String> registeredDeviceHash(String credentialId) {
    return jdbc.query("SELECT device_hash FROM device_binding WHERE credential_id=?",
        row -> row.next() ? Optional.of(row.getString(1)) : Optional.empty(), credentialId);
  }

  private static String hash(String proof) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }
}
