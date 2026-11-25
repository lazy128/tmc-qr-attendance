package tmc.access.domain.port;

public interface UsedTokenStore {
  boolean consume(String siteId, String jti, String eventId);

  boolean alreadyConsumed(String siteId, String jti);
}
