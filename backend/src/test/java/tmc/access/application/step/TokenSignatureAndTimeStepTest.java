package tmc.access.application.step;

import static org.assertj.core.api.Assertions.assertThat;
import static tmc.access.application.step.StepFixtures.*;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.Claims;
import tmc.access.infrastructure.security.Es256TokenCodec;

class TokenSignatureAndTimeStepTest {
  private static final Duration SKEW = Duration.ofSeconds(5);
  private static Es256TokenCodec issuer;
  private static Es256TokenCodec verifier;

  @BeforeAll
  static void configureKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var trusted = generator.generateKeyPair();
    var other = generator.generateKeyPair();
    issuer = new Es256TokenCodec(Map.of(), Map.of(
        KID, (ECPrivateKey) trusted.getPrivate(), "other-key", (ECPrivateKey) other.getPrivate()));
    verifier = new Es256TokenCodec(Map.of(KID, (ECPublicKey) trusted.getPublic()), Map.of());
  }

  @Test
  void acceptsSignedTokenAndStoresVerifiedClaims() {
    var context = new AccessContext();
    assertThat(execute(issuer.sign(CLAIMS), 0, context)).isEqualTo(StepResult.ok());
    assertThat(context.claims).isEqualTo(CLAIMS);
  }

  @Test
  void rejectsSignedTokenWithUnknownKid() {
    var unknown = claims(ISSUER, AUDIENCE, NOW, NOW, NOW.plusSeconds(30), "other-key");
    var context = new AccessContext();
    assertThat(execute(issuer.sign(unknown), 0, context)).isEqualTo(StepResult.deny(Reason.INVALID_TOKEN));
    assertThat(context.claims).isNull();
  }

  @Test
  void rejectsSignatureFromTheWrongKeyForAKnownKid() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var wrongSigner = new Es256TokenCodec(Map.of(), Map.of(KID, (ECPrivateKey) generator.generateKeyPair().getPrivate()));
    var context = new AccessContext();
    assertThat(execute(wrongSigner.sign(CLAIMS), 0, context)).isEqualTo(StepResult.deny(Reason.INVALID_TOKEN));
    assertThat(context.claims).isNull();
  }

  @Test
  void rejectsMalformedToken() {
    assertThat(execute("not-a-jwt", 0, new AccessContext())).isEqualTo(StepResult.deny(Reason.INVALID_TOKEN));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("claimCases")
  void checksAuthorityAndTime(String scenario, Claims input, StepResult expected) {
    assertThat(execute(issuer.sign(input), 0, new AccessContext())).isEqualTo(expected);
  }

  static Stream<Arguments> claimCases() {
    return Stream.of(
        Arguments.of("wrong issuer", claims("wrong", AUDIENCE, NOW, NOW, NOW.plusSeconds(30), KID),
            StepResult.deny(Reason.INVALID_TOKEN)),
        Arguments.of("wrong audience", claims(ISSUER, "wrong", NOW, NOW, NOW.plusSeconds(30), KID),
            StepResult.deny(Reason.INVALID_TOKEN)),
        Arguments.of("iat beyond skew", claims(ISSUER, AUDIENCE, NOW.plusSeconds(6), NOW, NOW.plusSeconds(30), KID),
            StepResult.deny(Reason.CLOCK_SKEW)),
        Arguments.of("iat and nbf at skew boundary",
            claims(ISSUER, AUDIENCE, NOW.plusSeconds(5), NOW.plusSeconds(5), NOW.plusSeconds(30), KID),
            StepResult.ok()),
        Arguments.of("nbf beyond skew", claims(ISSUER, AUDIENCE, NOW, NOW.plusSeconds(6), NOW.plusSeconds(30), KID),
            StepResult.deny(Reason.NOT_YET_VALID)),
        Arguments.of("exp at skew boundary",
            claims(ISSUER, AUDIENCE, NOW.minusSeconds(30), NOW.minusSeconds(30), NOW.minusSeconds(5), KID),
            StepResult.ok()),
        Arguments.of("exp beyond skew",
            claims(ISSUER, AUDIENCE, NOW.minusSeconds(30), NOW.minusSeconds(30), NOW.minusSeconds(6), KID),
            StepResult.deny(Reason.TOKEN_EXPIRED))
    );
  }

  @ParameterizedTest
  @ValueSource(longs = {-5, 5})
  void acceptsScanTimeAtSkewBoundary(long seconds) {
    assertThat(execute(issuer.sign(CLAIMS), seconds, new AccessContext())).isEqualTo(StepResult.ok());
  }

  @ParameterizedTest
  @ValueSource(longs = {-6, 6})
  void rejectsScanTimeBeyondSkew(long seconds) {
    assertThat(execute(issuer.sign(CLAIMS), seconds, new AccessContext())).isEqualTo(StepResult.deny(Reason.CLOCK_SKEW));
  }

  private static StepResult execute(String token, long scanOffset, AccessContext context) {
    var step = new TokenSignatureAndTimeStep(verifier, () -> NOW, SKEW, ISSUER, AUDIENCE);
    return step.execute(request("GATE-1", "SITE-1", REQUEST.direction(), NOW.plusSeconds(scanOffset), token, DEVICE),
        context);
  }
}
