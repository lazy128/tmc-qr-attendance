package tmc.attendance.api;

import java.time.YearMonth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.application.PermissionPolicy;
import tmc.attendance.infrastructure.PayrollBatches;
import tmc.shared.Identity;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
@RequestMapping("/attendance/v1/timesheets/payroll/{month}")
@ConditionalOnProperty(name = "tmc.accounts.enabled", havingValue = "true")
public class PayrollBatchController {
  private final PayrollBatches batches;
  private final Identity identity;
  private final PermissionPolicy permissions;
  private final AuditWriter audit;
  private final ClockProvider clock;

  public PayrollBatchController(PayrollBatches batches, Identity identity, PermissionPolicy permissions,
      AuditWriter audit, ClockProvider clock) {
    this.batches = batches;
    this.identity = identity;
    this.permissions = permissions;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping
  public ResponseEntity<PayrollBatches.History> history(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth, @PathVariable String month) {
    actor(auth);
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(batches.history(TimesheetController.month(month)));
  }

  @PostMapping
  public ResponseEntity<PayrollBatches.Batch> capture(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth, @PathVariable String month) {
    var actor = actor(auth);
    return batches.capture(TimesheetController.month(month), actor, CorrelationIdFilter.currentId(), clock.now())
        .map(item -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(item))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/{number}")
  public ResponseEntity<String> download(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @PathVariable String month, @PathVariable int number) {
    var actor = actor(auth);
    var period = TimesheetController.month(month);
    var csv = batches.csv(period, number).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    audit.append(actor, "PAYROLL_BATCH_DOWNLOAD", period + "/" + number, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + period + "-" + number + ".csv\"")
        .body(csv);
  }

  private String actor(String auth) {
    var actor = identity.authenticate(auth).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!permissions.permits(actor, "attendance:payroll-batch")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return actor;
  }
}
