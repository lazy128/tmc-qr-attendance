package tmc.access.domain.port;

public interface TokenValidator {
  Claims validate(String token);
}
