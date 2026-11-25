package tmc.access.domain.port;

import tmc.access.domain.AccessRequest;

public interface PolicyEvaluator {
  boolean allows(Claims claims, AccessRequest request);
}
