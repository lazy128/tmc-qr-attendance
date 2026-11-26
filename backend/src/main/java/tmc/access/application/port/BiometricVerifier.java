package tmc.access.application.port;

import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

@FunctionalInterface
public interface BiometricVerifier {
  enum FaceMatch { MATCH, NO_MATCH, SKIPPED }

  FaceMatch verify(String employeeId, SiteId siteId, StationId stationId);
}
