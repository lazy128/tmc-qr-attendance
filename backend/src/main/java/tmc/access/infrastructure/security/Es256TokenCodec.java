package tmc.access.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tmc.access.domain.Direction;
import tmc.access.domain.port.Claims;
import tmc.access.domain.port.TokenSigner;
import tmc.access.domain.port.TokenValidator;

public final class Es256TokenCodec implements TokenSigner, TokenValidator {
  private final Map<String, ECPublicKey> publicKeys;
  private final Map<String, ECPrivateKey> privateKeys;
  private final ObjectMapper json = new ObjectMapper();

  public Es256TokenCodec(Map<String, ECPublicKey> publicKeys, Map<String, ECPrivateKey> privateKeys) {
    this.publicKeys = Map.copyOf(publicKeys);
    this.privateKeys = Map.copyOf(privateKeys);
  }

  @Override
  public String sign(Claims claims) {
    try {
      var key = privateKeys.get(claims.keyId());
      if (key == null) {
        throw new IllegalArgumentException("unknown signing kid");
      }
      Map<String, Object> header = Map.of("alg", "ES256", "typ", "JWT", "kid", claims.keyId());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("iss", claims.issuer());
      payload.put("aud", claims.audience());
      payload.put("sub", claims.credentialId());
      payload.put("jti", claims.jti());
      payload.put("iat", claims.issuedAt().getEpochSecond());
      payload.put("nbf", claims.notBefore().getEpochSecond());
      payload.put("exp", claims.expiresAt().getEpochSecond());
      payload.put("cver", claims.version());
      payload.put("did", claims.deviceHash());
      if (claims.attendanceDirection() != null) payload.put("dir", claims.attendanceDirection().name());
      String body = encode(json.writeValueAsBytes(header)) + "." + encode(json.writeValueAsBytes(payload));
      Signature signature = Signature.getInstance("SHA256withECDSA");
      signature.initSign(key);
      signature.update(body.getBytes(StandardCharsets.US_ASCII));
      return body + "." + encode(derToJose(signature.sign()));
    } catch (Exception failed) {
      throw new IllegalStateException("token signing failed", failed);
    }
  }

  @Override
  public Claims validate(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        throw new IllegalArgumentException("malformed JWT");
      }
      var header = json.readTree(decode(parts[0]));
      if (!"ES256".equals(header.path("alg").asText())) {
        throw new IllegalArgumentException("algorithm rejected");
      }
      String kid = header.path("kid").asText();
      ECPublicKey key = publicKeys.get(kid);
      if (key == null) {
        throw new IllegalArgumentException("unknown kid");
      }
      Signature signature = Signature.getInstance("SHA256withECDSA");
      signature.initVerify(key);
      signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!signature.verify(joseToDer(decode(parts[2])))) {
        throw new IllegalArgumentException("bad signature");
      }
      var payload = json.readTree(decode(parts[1]));
      return new Claims(
          payload.path("iss").asText(),
          payload.path("aud").asText(),
          payload.path("sub").asText(),
          payload.path("jti").asText(),
          Instant.ofEpochSecond(payload.path("iat").asLong()),
          Instant.ofEpochSecond(payload.path("nbf").asLong()),
          Instant.ofEpochSecond(payload.path("exp").asLong()),
          payload.path("cver").asInt(),
          payload.path("did").asText(),
          kid,
          null,
          payload.has("dir") ? Direction.valueOf(payload.path("dir").asText()) : null
      );
    } catch (Exception failed) {
      throw new IllegalArgumentException("invalid token", failed);
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] decode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }

  private static byte[] derToJose(byte[] der) {
    int index = 0;
    if ((der[index++] & 255) != 0x30) {
      throw new IllegalArgumentException("bad DER sequence");
    }
    index++;
    if ((der[index++] & 255) != 2) {
      throw new IllegalArgumentException("bad DER r");
    }
    int lengthR = der[index++] & 255;
    byte[] r = Arrays.copyOfRange(der, index, index + lengthR);
    index += lengthR;
    if ((der[index++] & 255) != 2) {
      throw new IllegalArgumentException("bad DER s");
    }
    int lengthS = der[index++] & 255;
    byte[] s = Arrays.copyOfRange(der, index, index + lengthS);
    return ByteBuffer.allocate(64).put(fixed32(r)).put(fixed32(s)).array();
  }

  private static byte[] fixed32(byte[] number) {
    int start = number.length > 32 && number[0] == 0 ? 1 : 0;
    int size = number.length - start;
    if (size > 32) {
      throw new IllegalArgumentException("ECDSA integer too large");
    }
    byte[] out = new byte[32];
    System.arraycopy(number, start, out, 32 - size, size);
    return out;
  }

  private static byte[] joseToDer(byte[] raw) {
    if (raw.length != 64) {
      throw new IllegalArgumentException("signature length");
    }
    byte[] r = integer(Arrays.copyOfRange(raw, 0, 32));
    byte[] s = integer(Arrays.copyOfRange(raw, 32, 64));
    int length = 2 + r.length + 2 + s.length;
    return ByteBuffer.allocate(2 + length)
        .put((byte) 0x30)
        .put((byte) length)
        .put((byte) 2)
        .put((byte) r.length)
        .put(r)
        .put((byte) 2)
        .put((byte) s.length)
        .put(s)
        .array();
  }

  private static byte[] integer(byte[] value) {
    int index = 0;
    while (index < value.length - 1 && value[index] == 0) {
      index++;
    }
    byte[] trimmed = Arrays.copyOfRange(value, index, value.length);
    if ((trimmed[0] & 128) != 0) {
      byte[] padded = new byte[trimmed.length + 1];
      System.arraycopy(trimmed, 0, padded, 1, trimmed.length);
      return padded;
    }
    return trimmed;
  }
}
