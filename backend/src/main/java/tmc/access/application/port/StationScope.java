package tmc.access.application.port;

import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

public interface StationScope {
  String siteFor(StationId stationId);

  boolean allowed(StationId stationId, SiteId siteId);
}
