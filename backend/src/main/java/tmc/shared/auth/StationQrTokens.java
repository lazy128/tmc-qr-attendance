package tmc.shared.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class StationQrTokens {
  private static final JOSEObjectType TYPE = new JOSEObjectType("tmc-station-qr+jwt");
  private static final String ISSUER = "tmc-workplace";
  private static final String AUDIENCE = "tmc-station-check-in";
  private static final long TTL_SECONDS = 30;
  private final MACSigner signer;
  private final MACVerifier verifier;

  public StationQrTokens(String secret) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update("tmc-station-qr\0".getBytes(StandardCharsets.UTF_8));
      byte[] key = digest.digest(Base64.getDecoder().decode(secret));
      signer = new MACSigner(key);
      verifier = new MACVerifier(key);
    } catch (NoSuchAlgorithmException | JOSEException invalid) {
      throw new IllegalStateException("Không thể cấu hình chữ ký mã QR tại cổng.", invalid);
    }
  }

  public Issued issue(String stationId, String siteId, String direction, Instant now) {
    Instant expires = now.plusSeconds(TTL_SECONDS).truncatedTo(ChronoUnit.SECONDS);
    var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(stationId)
        .jwtID(UUID.randomUUID().toString()).claim("site", siteId).claim("dir", direction)
        .issueTime(Date.from(now)).expirationTime(Date.from(expires)).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TYPE).build(), claims);
    try {
      jwt.sign(signer);
      return new Issued(jwt.serialize(), expires);
    } catch (JOSEException invalid) {
      throw new IllegalStateException("Không thể ký mã QR tại cổng.", invalid);
    }
  }

  public Optional<Proof> verify(String code, Instant now) {
    if (code == null || code.length() > 1024) return Optional.empty();
    try {
      var jwt = SignedJWT.parse(code);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !TYPE.equals(jwt.getHeader().getType())
          || !jwt.verify(verifier)) return Optional.empty();
      var claims = jwt.getJWTClaimsSet();
      if (!ISSUER.equals(claims.getIssuer()) || !List.of(AUDIENCE).equals(claims.getAudience())
          || claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now)
          || claims.getExpirationTime() == null || claims.getSubject() == null || claims.getJWTID() == null
          || claims.getStringClaim("site") == null || claims.getStringClaim("dir") == null) return Optional.empty();
      return Optional.of(new Proof(claims.getJWTID(), claims.getSubject(), claims.getStringClaim("site"),
          claims.getStringClaim("dir"), claims.getExpirationTime().toInstant()));
    } catch (ParseException | JOSEException invalid) {
      return Optional.empty();
    }
  }

  public record Issued(String code, Instant expiresAt) {}
  public record Proof(String id, String stationId, String siteId, String direction, Instant expiresAt) {}
}
