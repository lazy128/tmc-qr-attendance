package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tmc.shared.auth.StationQrTokens;

class StationQrTokensTest {
  private static final byte[] FIXTURE_KEY = new byte[32];
  private static final Instant NOW = Instant.parse("2026-07-01T03:00:00Z");
  private final StationQrTokens codec = new StationQrTokens(Base64.getEncoder().encodeToString(FIXTURE_KEY));

  @Test
  void rejectsAShortDecodedKey() {
    var shortKey = Base64.getEncoder().encodeToString(new byte[31]);
    assertThatIllegalArgumentException().isThrownBy(() -> new StationQrTokens(shortKey));
  }

  @Test
  void issuesFifteenSecondCodesAndChecksBothTimestamps() {
    var issued = codec.issue("BD-G01", "SITE-BD", "ENTRY", NOW);
    assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(15));
    assertThat(codec.verify(issued.code(), NOW)).hasValueSatisfying(proof -> {
      assertThat(proof.stationId()).isEqualTo("BD-G01");
      assertThat(proof.siteId()).isEqualTo("SITE-BD");
      assertThat(proof.direction()).isEqualTo("ENTRY");
    });
    assertThat(codec.verify(issued.code(), NOW.minusSeconds(5))).isPresent();
    assertThat(codec.verify(issued.code(), NOW.minusSeconds(6))).isEmpty();
    assertThat(codec.verify(issued.code(), NOW.plusSeconds(19))).isPresent();
    assertThat(codec.verify(issued.code(), NOW.plusSeconds(20))).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
      "HS256, wrong-type, tmc-workplace, tmc-station-check-in",
      "HS256, tmc-station-qr+jwt, wrong-issuer, tmc-station-check-in",
      "HS256, tmc-station-qr+jwt, tmc-workplace, wrong-audience",
      "HS384, tmc-station-qr+jwt, tmc-workplace, tmc-station-check-in"
  })
  void rejectsWrongHeadersAndClaims(String algorithm, String type, String issuer, String audience) throws Exception {
    var original = SignedJWT.parse(codec.issue("BD-G01", "SITE-BD", "ENTRY", NOW).code()).getJWTClaimsSet();
    var claims = new JWTClaimsSet.Builder(original).issuer(issuer).audience(audience).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.parse(algorithm)).type(new JOSEObjectType(type)).build(), claims);
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update("tmc-station-qr\0".getBytes(StandardCharsets.UTF_8));
    jwt.sign(new MACSigner("HS256".equals(algorithm) ? digest.digest(FIXTURE_KEY) : new byte[48]));
    assertThat(codec.verify(jwt.serialize(), NOW)).isEmpty();
  }

  @Test
  void rejectsAValidCodeSignedByAnotherKey() {
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    var other = new StationQrTokens(Base64.getEncoder().encodeToString(otherKey));
    assertThat(codec.verify(other.issue("BD-G01", "SITE-BD", "ENTRY", NOW).code(), NOW)).isEmpty();
  }
}
