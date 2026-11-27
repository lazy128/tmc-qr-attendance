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
  private static final long TTL_SECONDS = 15;
  private static final long SKEW_SECONDS = 5;
  private final MACSigner signer;
  private final MACVerifier verifier;

  public StationQrTokens(String secret) {
    byte[] decoded = Base64.getDecoder().decode(secret);
    if (decoded.length < 32) throw new IllegalArgumentException("Station QR signing key must be at least 32 bytes");
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update("tmc-station-qr\0".getBytes(StandardCharsets.UTF_8));
      byte[] key = digest.digest(decoded);
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
          || claims.getIssueTime() == null || claims.getExpirationTime() == null
          || claims.getSubject() == null || claims.getJWTID() == null) return Optional.empty();
      var issuedAt = claims.getIssueTime().toInstant();
      var expiresAt = claims.getExpirationTime().toInstant();
      if (issuedAt.isAfter(now.plusSeconds(SKEW_SECONDS)) || !expiresAt.isAfter(now.minusSeconds(SKEW_SECONDS))
          || !expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(TTL_SECONDS))) return Optional.empty();
      String site = claims.getStringClaim("site");
      String direction = claims.getStringClaim("dir");
      if (site == null || !("ENTRY".equals(direction) || "EXIT".equals(direction))) return Optional.empty();
      return Optional.of(new Proof(claims.getJWTID(), claims.getSubject(), site, direction, expiresAt));
    } catch (ParseException | JOSEException invalid) {
      return Optional.empty();
    }
  }

  public record Issued(String code, Instant expiresAt) {}
  public record Proof(String id, String stationId, String siteId, String direction, Instant expiresAt) {}
}
