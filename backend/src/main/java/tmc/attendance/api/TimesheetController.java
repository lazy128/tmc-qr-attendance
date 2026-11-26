package tmc.attendance.api;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.TimesheetRow;
import tmc.attendance.infrastructure.JdbcTimesheets;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.attendance.infrastructure.XlsxTimesheetSink;
import tmc.shared.DemoIdentity;
import tmc.shared.StaffDirectory;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class TimesheetController {
  private final TimesheetService timesheets;
  private final JdbcTimesheets store;
  private final XlsxTimesheetSink xlsx;
  private final TimesheetCalculator calculator;
  private final DemoIdentity identity;
  private final PermissionPolicy permissions;
  private final ScopeResolver scopes;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final String workbookActor;

  public TimesheetController(TimesheetService timesheets, JdbcTimesheets store, XlsxTimesheetSink xlsx,
      TimesheetCalculator calculator, DemoIdentity identity, PermissionPolicy permissions, ScopeResolver scopes,
      AuditWriter audit, ClockProvider clock, @Value("${tmc.workflow.demo:false}") boolean demo) {
    this.timesheets = timesheets;
    this.store = store;
    this.xlsx = xlsx;
    this.calculator = calculator;
    this.identity = identity;
    this.permissions = permissions;
    this.scopes = scopes;
    this.audit = audit;
    this.clock = clock;
    this.workbookActor = demo ? "work-hr" : "hr";
  }

  @GetMapping("/attendance/v1/timesheets")
  public List<TimesheetRow> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month, @RequestParam(required = false) String employeeId) {
    var actor = authorize(auth, "attendance:read");
    var visible = visible(actor);
    if (employeeId != null) requireEmployee(visible, employeeId);
    var period = month(month);
    audit(actor, "TIMESHEET_READ", period.toString());
    return timesheets.month(period).stream().filter(row -> visible.contains(row.employeeId()))
        .filter(row -> employeeId == null || employeeId.equals(row.employeeId())).toList();
  }

  @GetMapping("/attendance/v1/timesheets/employees")
  public List<EmployeeOption> employees(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
    return visible(authorize(auth, "attendance:read")).stream().sorted()
        .map(id -> new EmployeeOption(id, StaffDirectory.name(id))).toList();
  }

  @GetMapping("/attendance/v1/timesheets/details")
  public List<AttendanceRecord> details(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String employeeId, @RequestParam LocalDate date) {
    var actor = authorize(auth, "attendance:read");
    requireEmployee(visible(actor), employeeId);
    audit(actor, "TIMESHEET_DETAILS", employeeId + "/" + date);
    return timesheets.scans(employeeId, date);
  }

  @PostMapping("/attendance/v1/timesheets/corrections")
  public TimesheetRow correct(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestBody Correction request) {
    String actor = authorize(auth, "attendance:correct");
    requireEmployee(visible(actor), request.employeeId());
    if (request.date() == null) throw new IllegalArgumentException("Chọn ngày công.");
    return timesheets.correctExit(request.employeeId(), request.date(), request.exitAt(), request.reason(),
        actor, CorrelationIdFilter.currentId(), clock.now());
  }

  @GetMapping("/attendance/v1/timesheets/export-status")
  public ExportStatus status(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month) {
    workbookActor(auth);
    return store.publication(month(month)).map(item -> new ExportStatus(item.status(), item.updatedAt()))
        .orElse(new ExportStatus("PENDING", null));
  }

  @GetMapping("/attendance/v1/timesheets/xlsx")
  public ResponseEntity<FileSystemResource> xlsx(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month) {
    var actor = workbookActor(auth);
    var period = month(month);
    var file = xlsx.file(period);
    if (!store.publication(period).map(item -> "SUCCESS".equals(item.status())).orElse(false)
        || !Files.isRegularFile(file)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Bảng tính đang chờ cập nhật.");
    }
    audit(actor, "TIMESHEET_XLSX_DOWNLOAD", period.toString());
    return attachment("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file.getFileName().toString())
        .body(new FileSystemResource(file));
  }

  @GetMapping("/attendance/v1/timesheets/csv")
  public ResponseEntity<String> csv(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month) {
    var actor = authorize(auth, "attendance:export");
    var period = month(month);
    var visible = visible(actor);
    var csv = new StringBuilder("\uFEFFMã nhân viên,Họ tên,Ngày,Giờ vào,Giờ ra,Tổng giờ (HH:mm),Trạng thái\r\n");
    for (var row : timesheets.month(period)) {
      if (!visible.contains(row.employeeId())) continue;
      csv.append(csvCell(row.employeeId())).append(',').append(csvCell(row.employeeName())).append(',')
          .append(row.workDate()).append(',').append(timestamp(row.firstIn())).append(',').append(timestamp(row.lastOut()))
          .append(',').append("%d:%02d".formatted(row.workedMinutes() / 60, row.workedMinutes() % 60))
          .append(',').append(csvCell(row.status().label())).append("\r\n");
    }
    audit(actor, "TIMESHEET_CSV_DOWNLOAD", period.toString());
    return attachment("text/csv;charset=UTF-8", "timesheet-" + period + ".csv").body(csv.toString());
  }

  private String workbookActor(String header) {
    String actor = authorize(header, "attendance:export");
    if (!actor.equals(workbookActor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bảng tính thuộc phạm vi HR khác.");
    return actor;
  }

  private Set<String> visible(String actor) { return scopes.visibleEmployeeIds(actor, "Timesheet"); }

  private String authorize(String header, String permission) {
    var actor = identity.authenticate(header).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!permissions.permits(actor, permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return actor;
  }

  private static void requireEmployee(Set<String> employees, String id) {
    if (id == null || !employees.contains(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private static YearMonth month(String text) {
    try {
      if (!text.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("Dùng YYYY-MM");
      return YearMonth.parse(text);
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("Dùng YYYY-MM");
    }
  }

  private static ResponseEntity.BodyBuilder attachment(String contentType, String filename) {
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  private String timestamp(Instant value) {
    return value == null ? "" : value.atZone(calculator.zone()).toOffsetDateTime().toString();
  }

  private static String csvCell(String value) {
    if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private void audit(String actor, String action, String target) {
    audit.append(actor, action, target, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
  }

  public record EmployeeOption(String employeeId, String employeeName) {}
  public record Correction(String employeeId, LocalDate date, Instant exitAt, String reason) {}
  public record ExportStatus(String status, Instant updatedAt) {}
}
