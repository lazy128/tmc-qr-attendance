package tmc.bootstrap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.api.ApiError;
import tmc.access.infrastructure.persistence.JdbcStationRegistry;
import tmc.access.domain.port.CredentialReader;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.shared.DemoIdentity;
import tmc.shared.StaffDirectory;
import tmc.shared.StaffDirectory.Employee;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;
import tmc.userqr.application.IssueQrToken;
import tmc.userqr.infrastructure.JdbcDeviceBindings;

@RestController
@ConditionalOnProperty(name = "tmc.workflow.demo", havingValue = "true")
public class WorkflowDemoController {
  private final DemoIdentity identity;
  private final CredentialReader credentials;
  private final JdbcDeviceBindings devices;
  private final IssueQrToken issue;
  private final JdbcStationRegistry stations;
  private final TimesheetService timesheets;
  private final TimesheetCalculator calculator;
  private final AuditWriter audit;
  private final ClockProvider clock;

  public WorkflowDemoController(DemoIdentity identity, CredentialReader credentials, JdbcDeviceBindings devices,
      IssueQrToken issue, JdbcStationRegistry stations, TimesheetService timesheets, TimesheetCalculator calculator,
      AuditWriter audit, ClockProvider clock) {
    this.identity = identity;
    this.credentials = credentials;
    this.devices = devices;
    this.issue = issue;
    this.stations = stations;
    this.timesheets = timesheets;
    this.calculator = calculator;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping("/demo/v1/employees")
  public List<Employee> employees() { return StaffDirectory.employees(); }

  @GetMapping("/demo/v1/gates")
  public List<JdbcStationRegistry.Station> gates() {
    return stations.list().stream().filter(station -> station.status() == JdbcStationRegistry.Status.ACTIVE).toList();
  }

  @PostMapping("/demo/v1/session")
  public ResponseEntity<Map<String, String>> session(@RequestBody Session request) {
    if (request.role() == null) throw new IllegalArgumentException("Chọn vai trò demo.");
    String actor = switch (request.role()) {
      case "EMPLOYEE" -> "work-" + employee(request.employeeId()).id();
      case "MANAGER" -> "work-manager";
      case "HR" -> "work-hr";
      default -> throw new IllegalArgumentException("Vai trò demo không hợp lệ.");
    };
    String bearer = identity.workflowToken(actor).orElseThrow();
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("bearer", bearer));
  }

  @PostMapping("/demo/v1/employees/{id}/qr")
  public ResponseEntity<?> qr(@PathVariable String id) {
    var employee = employee(id);
    var credentialId = WorkflowDemoConfiguration.credentialId(id);
    var credential = credentials.getCredential(credentialId).orElseThrow();
    if (!credential.active()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("REVOKED", "Thẻ đã thu hồi",
          CorrelationIdFilter.currentId(), false));
    }
    var device = devices.registeredDeviceHash(credentialId).filter(hash -> hash.equals(credential.deviceHash()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Thiết bị demo chưa đăng ký"));
    var token = issue.issue(credentialId, device);
    var now = clock.now();
    audit.append("work-" + id, "QR_ISSUE", credentialId, "SUCCESS", CorrelationIdFilter.currentId(), now);
    var day = timesheets.calculate(id, calculator.workDate(now));
    String status = day.map(data -> data.unpairedEntry() != null ? "IN_SHIFT"
        : data.row().lastOut() != null ? "LEFT" : "NOT_ENTERED").orElse("NOT_ENTERED");
    var lastEntry = day.map(TimesheetCalculator.Calculation::lastEntry).orElse(null);
    var lastExit = day.map(data -> data.row().lastOut()).orElse(null);
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(new Pass(token.token(), token.expiresAt(), status, lastEntry, lastExit));
  }

  private static Employee employee(String id) {
    return StaffDirectory.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có nhân viên demo"));
  }

  public record Session(String role, String employeeId) {}
  public record Pass(String token, Instant expiresAt, String status, Instant lastEntry, Instant lastExit) {}
}
