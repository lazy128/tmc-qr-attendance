package tmc.attendance.api;

import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
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
import tmc.attendance.application.AttendanceCsv;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.TimesheetRow;
import tmc.attendance.infrastructure.JdbcTimesheets;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.attendance.infrastructure.XlsxTimesheetSink;
import tmc.shared.Identity;
import tmc.shared.EmployeeDirectory;
import tmc.shared.StaffDirectory;
import tmc.shared.WorkplaceSites;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class TimesheetController {
  private final TimesheetService timesheets;
  private final JdbcTimesheets store;
  private final XlsxTimesheetSink xlsx;
  private final TimesheetCalculator calculator;
  private final Identity identity;
  private final PermissionPolicy permissions;
  private final ScopeResolver scopes;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final String workbookActor;
  private final EmployeeDirectory employees;

  public TimesheetController(TimesheetService timesheets, JdbcTimesheets store, XlsxTimesheetSink xlsx,
      TimesheetCalculator calculator, Identity identity, PermissionPolicy permissions, ScopeResolver scopes,
      AuditWriter audit, ClockProvider clock, EmployeeDirectory employees,
      @Value("${tmc.workflow.demo:false}") boolean demo, @Value("${tmc.accounts.enabled:false}") boolean workplace) {
    this.timesheets = timesheets;
    this.store = store;
    this.xlsx = xlsx;
    this.calculator = calculator;
    this.identity = identity;
    this.permissions = permissions;
    this.scopes = scopes;
    this.audit = audit;
    this.clock = clock;
    this.workbookActor = workplace ? null : demo ? "work-hr" : "hr";
    this.employees = employees;
  }

  @GetMapping("/attendance/v1/timesheets")
  public List<TimesheetRow> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month, @RequestParam(required = false) String employeeId,
      @RequestParam(required = false) String siteId) {
    var actor = authorize(auth, "attendance:read");
    var period = month(month);
    var rows = rows(actor, period, employeeId, siteId);
    audit(actor, "TIMESHEET_READ", reportTarget(period, siteId));
    return rows;
  }

  @GetMapping("/attendance/v1/timesheets/employees")
  public List<EmployeeOption> employees(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam(required = false) String siteId) {
    var actor = authorize(auth, "attendance:read");
    validateSite(siteId);
    return visible(actor).stream().sorted()
        .filter(id -> siteId == null || employees.find(id).filter(employee -> matchesSite(siteId, employee.siteId())).isPresent())
        .map(id -> new EmployeeOption(id, employees.name(id))).toList();
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
  public ResponseEntity<Resource> xlsx(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month, @RequestParam(required = false) String siteId,
      @RequestParam(required = false) String employeeId) throws IOException {
    var actor = workbookActor(auth);
    var period = month(month);
    String contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if (workbookActor == null || siteId != null || employeeId != null) {
      var report = new ByteArrayResource(xlsx.export(rows(actor, period, employeeId, siteId)));
      audit(actor, "TIMESHEET_XLSX_DOWNLOAD", reportTarget(period, siteId));
      return attachment(contentType, reportName(period, siteId, "xlsx")).body(report);
    }
    var file = xlsx.file(period);
    if (!store.publication(period).map(item -> "SUCCESS".equals(item.status())).orElse(false)
        || !Files.isRegularFile(file)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Bảng tính đang chờ cập nhật.");
    }
    audit(actor, "TIMESHEET_XLSX_DOWNLOAD", period.toString());
    return attachment(contentType, file.getFileName().toString())
        .body(new FileSystemResource(file));
  }

  @GetMapping("/attendance/v1/timesheets/csv")
  public ResponseEntity<String> csv(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestParam String month, @RequestParam(required = false) String siteId,
      @RequestParam(required = false) String employeeId) {
    var actor = authorize(auth, "attendance:export");
    var period = month(month);
    var csv = new StringBuilder("\uFEFFMã nhân viên,Họ tên,Ngày,Giờ vào,Giờ ra,Tổng giờ (HH:mm),Trạng thái,Nhà máy\r\n");
    for (var row : rows(actor, period, employeeId, siteId)) {
      csv.append(AttendanceCsv.field(row.employeeId())).append(',').append(AttendanceCsv.field(row.employeeName())).append(',')
          .append(row.workDate()).append(',').append(timestamp(row.firstIn())).append(',').append(timestamp(row.lastOut()))
          .append(',').append("%d:%02d".formatted(row.workedMinutes() / 60, row.workedMinutes() % 60))
          .append(',').append(AttendanceCsv.field(row.status().label())).append(',')
          .append(AttendanceCsv.field(WorkplaceSites.name(row.siteId()))).append("\r\n");
    }
    audit(actor, "TIMESHEET_CSV_DOWNLOAD", reportTarget(period, siteId));
    return attachment("text/csv;charset=UTF-8", reportName(period, siteId, "csv")).body(csv.toString());
  }

  private List<TimesheetRow> rows(String actor, YearMonth period, String employeeId, String siteId) {
    validateSite(siteId);
    var allowed = visible(actor);
    if (employeeId != null) requireEmployee(allowed, employeeId);
    return timesheets.month(period).stream().filter(row -> allowed.contains(row.employeeId()))
        .filter(row -> employeeId == null || employeeId.equals(row.employeeId()))
        .filter(row -> matchesSite(siteId, row.siteId())).toList();
  }

  private static void validateSite(String siteId) {
    if (siteId != null && !"SITE-1".equals(siteId) && WorkplaceSites.ALL.stream().noneMatch(site -> site.id().equals(siteId))) {
      throw new IllegalArgumentException("Chọn nhà máy hợp lệ.");
    }
  }

  private static boolean matchesSite(String filter, String actual) {
    return filter == null || StaffDirectory.siteFor(filter).equals(StaffDirectory.siteFor(actual));
  }

  private static String reportTarget(YearMonth period, String site) {
    return period + (site == null ? "" : "/" + site);
  }

  private static String reportName(YearMonth period, String site, String extension) {
    return "timesheet-" + period + (site == null ? "" : "-" + site) + "." + extension;
  }

  private String workbookActor(String header) {
    String actor = authorize(header, "attendance:export");
    if (workbookActor != null && !actor.equals(workbookActor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bảng tính thuộc phạm vi HR khác.");
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

  static YearMonth month(String text) {
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

  private void audit(String actor, String action, String target) {
    audit.append(actor, action, target, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
  }

  public record EmployeeOption(String employeeId, String employeeName) {}
  public record Correction(String employeeId, LocalDate date, Instant exitAt, String reason) {}
  public record ExportStatus(String status, Instant updatedAt) {}
}
