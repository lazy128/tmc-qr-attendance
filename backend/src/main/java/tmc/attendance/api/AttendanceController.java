package tmc.attendance.api;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;
import tmc.attendance.application.AttendanceCsv;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.attendance.domain.AttendanceRecord;
import tmc.shared.DemoIdentity;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class AttendanceController {
  private final AttendanceRecordStore records;
  private final DemoIdentity identity;
  private final PermissionPolicy permissions;
  private final ScopeResolver scopes;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final ZoneId zone;

  public AttendanceController(
      AttendanceRecordStore records, DemoIdentity identity, PermissionPolicy permissions,
      ScopeResolver scopes, AuditWriter audit, ClockProvider clock,
      @Value("${tmc.hr.zone:Asia/Ho_Chi_Minh}") String zone
  ) {
    this.records = records;
    this.identity = identity;
    this.permissions = permissions;
    this.scopes = scopes;
    this.audit = audit;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  @GetMapping("/attendance/v1/records")
  public List<AttendanceRecord> records(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(required = false) String employeeId
  ) {
    String actor = authorize(authorization, "attendance:read");
    Set<String> visible = scopes.visibleEmployeeIds(actor, switch (actor) {
      case "employee", "employee-2" -> "Own";
      case "manager" -> "Team";
      default -> "Authorized";
    });
    if (employeeId != null && !visible.contains(employeeId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee is outside your attendance scope");
    }
    List<AttendanceRecord> result = visibleRecords(visible).stream()
        .filter(row -> employeeId == null || employeeId.equals(row.employeeId()))
        .toList();
    audit(actor, "ATTENDANCE_READ", "attendance");
    return result;
  }

  @GetMapping("/reports/v1/exports")
  public ResponseEntity<String> export(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam String month
  ) {
    String actor = authorize(authorization, "attendance:export");
    YearMonth period;
    try {
      if (!month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("Use YYYY-MM");
      period = YearMonth.parse(month);
    } catch (DateTimeParseException invalidMonth) {
      throw new IllegalArgumentException("Use YYYY-MM");
    }
    String csv = AttendanceCsv.render(visibleRecords(scopes.visibleEmployeeIds(actor, "Authorized")), period, zone);
    audit(actor, "HR_EXPORT_DOWNLOAD", period.toString());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance-" + period + ".csv\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(csv);
  }

  private List<AttendanceRecord> visibleRecords(Set<String> visible) {
    return records.all().stream()
        .filter(row -> row.employeeId() != null && visible.contains(row.employeeId()))
        .toList();
  }

  private String authorize(String authorization, String action) {
    String actor = identity.authenticate(authorization)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enter a valid demo bearer token"));
    if (!permissions.permits(actor, action)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
    }
    return actor;
  }

  private void audit(String actor, String action, String target) {
    audit.append(actor, action, target, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
  }
}
