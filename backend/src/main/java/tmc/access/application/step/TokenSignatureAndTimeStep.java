package tmc.access.application.step;

import java.time.Duration;
import tmc.access.application.AccessContext;
import tmc.access.application.StepResult;
import tmc.access.application.VerificationStep;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.TokenValidator;
import tmc.shared.clock.ClockProvider;

public final class TokenSignatureAndTimeStep implements VerificationStep {
  private final TokenValidator validator;
  private final ClockProvider clock;
  private final Duration ttlSkew;
  private final String expectedIssuer;
  private final String expectedAudience;

  public TokenSignatureAndTimeStep(
      TokenValidator validator,
      ClockProvider clock,
      Duration ttlSkew,
      String expectedIssuer,
      String expectedAudience
  ) {
    this.validator = validator;
    this.clock = clock;
    this.ttlSkew = ttlSkew;
    this.expectedIssuer = expectedIssuer;
    this.expectedAudience = expectedAudience;
  }

  @Override
  public int order() {
    return 30;
  }

  @Override
  public String name() {
    return "TokenSignatureAndTime";
  }

  @Override
  public StepResult execute(AccessRequest request, AccessContext context) {
    try {
      var claims = validator.validate(request.token());
      context.claims = claims;
      if (!expectedIssuer.equals(claims.issuer()) || !expectedAudience.equals(claims.audience())) {
        return StepResult.deny(Reason.INVALID_TOKEN);
      }
      var now = clock.now();
      if (claims.issuedAt().isAfter(now.plus(ttlSkew))
          || Duration.between(request.scannedAt(), now).abs().compareTo(ttlSkew) > 0) {
        return StepResult.deny(Reason.CLOCK_SKEW);
      }
      if (now.plus(ttlSkew).isBefore(claims.notBefore())) {
        return StepResult.deny(Reason.NOT_YET_VALID);
      }
      if (now.minus(ttlSkew).isAfter(claims.expiresAt())) {
        return StepResult.deny(Reason.TOKEN_EXPIRED);
      }
      return StepResult.ok();
    } catch (RuntimeException ignored) {
      return StepResult.deny(Reason.INVALID_TOKEN);
    }
  }
}
