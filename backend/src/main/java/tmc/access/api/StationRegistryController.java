package tmc.access.api;

import java.util.List;
import java.util.Set;
import java.time.LocalTime;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.access.domain.Direction;
import tmc.access.domain.Gate;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.access.infrastructure.persistence.JdbcStationRegistry.Station;
import tmc.access.infrastructure.persistence.JdbcStationRegistry.Status;
import tmc.shared.Identity;
import tmc.shared.StaffDirectory.Group;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
@RequestMapping("/admin/v1/stations")
public class StationRegistryController {
  private final JdbcStationRegistry stations;
  private final Identity identity;
  private final PermissionPolicy permissions;
  private final AuditWriter audit;
  private final ClockProvider clock;

  public StationRegistryController(JdbcStationRegistry stations, Identity identity, PermissionPolicy permissions,
      AuditWriter audit, ClockProvider clock) {
    this.stations = stations;
    this.identity = identity;
    this.permissions = permissions;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping
  public StationList list(@RequestHeader(value = "Authorization", required = false) String authorization) {
    String actor = authenticate(authorization);
    if (permissions.permits(actor, "stations:manage")) return new StationList(stations.list(), true, stations.site());
    if (permissions.permits(actor, "stations:read-assigned")) {
      return new StationList(stations.find(identity.assignedStation(actor).orElse(stations.assignedStation())).stream().toList(), false, stations.site());
    }
    throw forbidden();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Station create(@RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody CreateStation body) {
    String actor = manager(authorization);
    if (!stations.site().equals(body.siteId())) throw new IllegalArgumentException("Station must belong to this Edge's site");
    validateId(body.stationId(), "stationId");
    validateId(body.gateId(), "gateId");
    if (!stations.create(body.stationId(), body.gateId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Station already registered");
    }
    if (body.gate() != null) stations.setGate(body.stationId(), gate(body.gate()));
    audit(actor, "STATION_CREATE", body.stationId());
    return stations.find(body.stationId()).orElseThrow();
  }

  @PatchMapping("/{id}")
  @Transactional
  public Station update(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id, @RequestBody UpdateStation body) {
    String actor = manager(authorization);
    validateId(id, "stationId");
    if (body.gateId() == null && body.status() == null && body.gate() == null) throw new IllegalArgumentException("No station changes supplied");
    if (body.gateId() != null) validateId(body.gateId(), "gateId");
    stations.update(id, body.gateId(), body.status())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));
    if (body.gate() != null) stations.setGate(id, gate(body.gate()));
    audit(actor, "STATION_UPDATE", id);
    return stations.find(id).orElseThrow();
  }

  private String authenticate(String authorization) {
    return identity.authenticate(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid bearer token required"));
  }

  private String manager(String authorization) {
    String actor = authenticate(authorization);
    if (!permissions.permits(actor, "stations:manage")) throw forbidden();
    return actor;
  }

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
  }

  private void audit(String actor, String action, String id) {
    audit.append(actor, action, id, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
  }

  private static void validateId(String id, String field) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
      throw new IllegalArgumentException("Missing or invalid " + field);
    }
  }

  private Gate gate(GateSettings options) {
    if (options.name() == null || options.name().isBlank() || options.name().length() > 150
        || options.type() == null || options.allowedGroups() == null || options.allowedGroups().isEmpty()
        || options.directions() == null || options.directions().size() != 1
        || (options.opensAt() == null) != (options.closesAt() == null)) {
      throw new IllegalArgumentException("Supply a gate name, type, groups, one direction and an optional time range.");
    }
    return new Gate(options.name(), stations.site(), options.type(), options.allowedGroups(),
        options.directions(), options.opensAt(), options.closesAt());
  }

  public record GateSettings(String name, Gate.Type type, Set<Group> allowedGroups, Set<Direction> directions,
      LocalTime opensAt, LocalTime closesAt) {}
  public record CreateStation(String siteId, String stationId, String gateId, GateSettings gate) {}
  public record UpdateStation(String gateId, Status status, GateSettings gate) {}
  public record StationList(List<Station> stations, boolean canManage, String siteId) {}
}
