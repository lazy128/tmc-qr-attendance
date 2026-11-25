package tmc.access.domain.port;

public interface TokenSigner {
  String sign(Claims claims);
}
