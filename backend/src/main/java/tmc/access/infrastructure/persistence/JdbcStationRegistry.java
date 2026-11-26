package tmc.access.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tmc.access.application.port.StationAuthenticator;
import tmc.access.application.port.StationScope;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

public final class JdbcStationRegistry implements StationAuthenticator, StationScope {
  public enum Status { ACTIVE, DISABLED }

  public record Station(String siteId, String stationId, String gateId, Status status, Instant lastHeartbeatAt) {}

  private static final RowMapper<Station> MAPPER = (row, index) -> {
    var heartbeat = row.getObject("last_heartbeat_at", OffsetDateTime.class);
    return new Station(row.getString("site_id"), row.getString("station_id"), row.getString("gate_id"),
        Status.valueOf(row.getString("status")), heartbeat == null ? null : heartbeat.toInstant());
  };
  private final JdbcTemplate jdbc;
  private final String site;
  private final String assignedStation;

  public JdbcStationRegistry(JdbcTemplate jdbc, String site, String assignedStation) {
    this.jdbc = jdbc;
    this.site = site;
    this.assignedStation = assignedStation;
    create(assignedStation, assignedStation);
  }

  public String site() { return site; }

  public String assignedStation() { return assignedStation; }

  public List<Station> list() {
    return jdbc.query("SELECT * FROM station_registry WHERE site_id = ? ORDER BY station_id", MAPPER, site);
  }

  public Optional<Station> find(String id) {
    return jdbc.query("SELECT * FROM station_registry WHERE site_id = ? AND station_id = ?", MAPPER, site, id)
        .stream().findFirst();
  }

  public boolean create(String id, String gate) {
    try {
      jdbc.update("INSERT INTO station_registry (site_id, station_id, gate_id, status) VALUES (?, ?, ?, 'ACTIVE')",
          site, id, gate);
      return true;
    } catch (DuplicateKeyException duplicate) {
      return false;
    }
  }

  public Optional<Station> update(String id, String gate, Status status) {
    int updated = jdbc.update("""
        UPDATE station_registry SET gate_id = COALESCE(?, gate_id), status = COALESCE(?, status)
        WHERE site_id = ? AND station_id = ?
        """, gate, status == null ? null : status.name(), site, id);
    return updated == 0 ? Optional.empty() : find(id);
  }

  @Override
  public boolean authenticated(AccessRequest request) {
    return request.stationId() != null && siteFor(request.stationId()) != null;
  }

  @Override
  public String siteFor(StationId id) {
    return find(id.value()).filter(station -> station.status() == Status.ACTIVE).map(Station::siteId).orElse(null);
  }

  @Override
  public boolean allowed(StationId station, SiteId requestedSite) {
    return station != null && requestedSite != null && requestedSite.value().equals(siteFor(station));
  }
}
