package tmc.shared.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

final class SessionTokens {
  private static final String ISSUER = "tmc-workplace";
  private static final String AUDIENCE = "tmc-web-session";
  private final MACSigner signer;
  private final MACVerifier verifier;

  SessionTokens(String secret) {
    try {
      byte[] key = Base64.getDecoder().decode(secret);
      if (key.length < 32) throw new IllegalArgumentException("Key too short");
      signer = new MACSigner(key);
      verifier = new MACVerifier(key);
    } catch (IllegalArgumentException | JOSEException invalid) {
      throw new IllegalArgumentException("TMC_SESSION_KEY phải chứa ít nhất 32 byte ngẫu nhiên, mã hóa Base64.");
    }
  }

  String issue(String username, String id, Instant now, Instant expiry) {
    var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(username)
        .jwtID(id).issueTime(Date.from(now)).expirationTime(Date.from(expiry)).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
    try {
      jwt.sign(signer);
      return jwt.serialize();
    } catch (JOSEException error) {
      throw new IllegalStateException("Không ký được phiên đăng nhập.", error);
    }
  }

  Optional<Claims> verify(String authorization, Instant now) {
    if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 2048) {
      return Optional.empty();
    }
    try {
      var jwt = SignedJWT.parse(authorization.substring(7));
      var header = jwt.getHeader();
      if (!JWSAlgorithm.HS256.equals(header.getAlgorithm()) || !JOSEObjectType.JWT.equals(header.getType())
          || !jwt.verify(verifier)) return Optional.empty();
      var data = jwt.getJWTClaimsSet();
      if (!ISSUER.equals(data.getIssuer()) || !List.of(AUDIENCE).equals(data.getAudience())
          || data.getExpirationTime() == null || !data.getExpirationTime().toInstant().isAfter(now)
          || data.getIssueTime() == null || data.getIssueTime().toInstant().isAfter(now)
          || data.getSubject() == null || data.getJWTID() == null) return Optional.empty();
      return Optional.of(new Claims(data.getSubject(), data.getJWTID()));
    } catch (ParseException | JOSEException invalid) {
      return Optional.empty();
    }
  }

  record Claims(String username, String id) {}
}
