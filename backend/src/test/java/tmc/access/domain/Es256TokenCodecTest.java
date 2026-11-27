package tmc.access.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tmc.access.domain.port.Claims;
import tmc.access.infrastructure.security.Es256TokenCodec;

class Es256TokenCodecTest {
  @Test
  void rejectsUnsignedTokensWrongAlgorithmsAndMissingOrUnknownKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var key = generator.generateKeyPair();
    var codec = new Es256TokenCodec(Map.of("test-key", (ECPublicKey) key.getPublic()),
        Map.of("test-key", (ECPrivateKey) key.getPrivate()));
    var issued = Instant.parse("2026-07-01T03:00:00Z");
    var claims = new Claims("tmc", "tmc-gates", "test-credential", "test-jti",
        issued, issued, issued.plusSeconds(30), 1, "test-device", "test-key", null);
    var token = codec.sign(claims);
    assertThat(codec.validate(token)).isEqualTo(claims);
    String[] parts = token.split("\\.");
    assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(
        header("{\"alg\":\"none\",\"kid\":\"test-key\"}") + "." + parts[1] + "."));
    assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(
        header("{\"alg\":\"ES256\"}") + "." + parts[1] + "." + parts[2]));
    assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(
        header("{\"alg\":\"ES256\",\"kid\":\"foreign-key\"}") + "." + parts[1] + "." + parts[2]));

    var signedWithWrongAlgorithm = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(),
        JWTClaimsSet.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)));
    signedWithWrongAlgorithm.sign(new MACSigner(key.getPublic().getEncoded()));
    assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(signedWithWrongAlgorithm.serialize()));
  }

  private String header(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}
