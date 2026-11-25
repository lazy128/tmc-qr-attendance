package tmc.access.application.port;

import tmc.access.domain.AccessRequest;

public interface StationAuthenticator {
  boolean authenticated(AccessRequest request);
}
