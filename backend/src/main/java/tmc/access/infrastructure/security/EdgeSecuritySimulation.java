package tmc.access.infrastructure.security;

import tmc.access.application.port.StationAuthenticator;
import tmc.access.application.port.StationScope;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

public final class EdgeSecuritySimulation implements StationAuthenticator, StationScope {
  private final String station;
  private final String site;

  public EdgeSecuritySimulation() {
    this("GATE-1", "SITE-1");
  }

  public EdgeSecuritySimulation(String station, String site) {
    this.station = station;
    this.site = site;
  }

  @Override
  public boolean authenticated(AccessRequest request) {
    return request.stationId() != null && station.equals(request.stationId().value());
  }

  @Override
  public String siteFor(StationId stationId) {
    return station.equals(stationId.value()) ? site : null;
  }

  @Override
  public boolean allowed(StationId station, SiteId site) {
    return station != null && site != null && site.value().equals(siteFor(station));
  }
}
