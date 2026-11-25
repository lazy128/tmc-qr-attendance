package tmc.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves configured demo bearer tokens to server-side identities. */
public final class DemoIdentity {
  private final Map<String, String> tokens;

  public DemoIdentity(Map<String, String> tokens) {
    this.tokens = Map.copyOf(tokens);
    Set<String> unique = new HashSet<>();
    for (String token : tokens.values()) {
      if (!token.isEmpty() && !unique.add(token)) {
        throw new IllegalArgumentException("Demo identities must have distinct tokens");
      }
    }
  }

  public Optional<String> authenticate(String header) {
    if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
    byte[] supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
    return tokens.entrySet().stream()
        .filter(entry -> !entry.getValue().isEmpty())
        .filter(entry -> MessageDigest.isEqual(entry.getValue().getBytes(StandardCharsets.UTF_8), supplied))
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
