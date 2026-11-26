package tmc.access.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tmc.access.application.port.StationAuthenticator;
import tmc.access.application.port.StationScope;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.Direction;
import tmc.access.domain.Gate;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import tmc.shared.StaffDirectory.Group;

public final class JdbcStationRegistry implements StationAuthenticator, StationScope {
  public enum Status { ACTIVE, DISABLED }

  public record Station(String siteId, String stationId, String gateId, Status status, Instant lastHeartbeatAt, Gate gate) {}

  private static final RowMapper<Station> MAPPER = (row, index) -> {
    var heartbeat = row.getObject("last_heartbeat_at", OffsetDateTime.class);
    String site = row.getString("site_id");
    String id = row.getString("station_id");
    String label = row.getString("display_name");
    var gate = new Gate(label == null ? id : label, site, Gate.Type.valueOf(row.getString("gate_type")),
        Arrays.stream(row.getString("allowed_groups").split(",")).map(Group::valueOf).collect(Collectors.toSet()),
        Arrays.stream(row.getString("directions").split(",")).map(Direction::valueOf).collect(Collectors.toSet()),
        row.getObject("opens_at", LocalTime.class), row.getObject("closes_at", LocalTime.class));
    return new Station(site, id, row.getString("gate_id"), Status.valueOf(row.getString("status")),
        heartbeat == null ? null : heartbeat.toInstant(), gate);
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

  public void seedGate(String id, Gate gate) {
    create(id, id);
    if (find(id).filter(station -> station.gate().name().equals(id)).isPresent()) setGate(id, gate);
  }

  public void setGate(String id, Gate gate) {
    jdbc.update("""
        UPDATE station_registry SET display_name=?, gate_type=?, allowed_groups=?, directions=?, opens_at=?, closes_at=?
        WHERE site_id=? AND station_id=?
        """, gate.name(), gate.type().name(), names(gate.allowedGroups()), names(gate.directions()),
        gate.opensAt(), gate.closesAt(), site, id);
  }

  private static String names(Iterable<? extends Enum<?>> values) {
    var names = new java.util.StringJoiner(",");
    for (var value : values) names.add(value.name());
    return names.toString();
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
