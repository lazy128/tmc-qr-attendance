package tmc.attendance.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.TimesheetRow;
import tmc.attendance.domain.TimesheetRow.Status;
import tmc.shared.StaffDirectory;

public record TimesheetCalculator(ZoneId zone, LocalTime dayStarts) {
  public LocalDate workDate(Instant timestamp) {
    var local = timestamp.atZone(zone);
    return local.toLocalTime().isBefore(dayStarts) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
  }

  public Instant start(LocalDate date) { return date.atTime(dayStarts).atZone(zone).toInstant(); }

  public record Calculation(TimesheetRow row, Instant unpairedEntry, Instant lastEntry) {}

  public Optional<Calculation> calculate(String employeeId, LocalDate date, List<AttendanceRecord> scans,
      Instant manualExit) {
    if (scans.isEmpty()) return Optional.empty();
    var ordered = new ArrayList<>(scans);
    ordered.sort(Comparator.comparing(AttendanceRecord::scannedAt).thenComparing(AttendanceRecord::eventId));
    var site = ordered.stream().filter(scan -> scan.eligible() && !scan.suspicious())
        .findFirst().orElse(ordered.getFirst()).siteId();
    if (manualExit != null) {
      ordered.add(new AttendanceRecord("hr-adjustment", employeeId, site, "HR",
          "EXIT", manualExit, "ACKED", "NOT_AVAILABLE", null, true, false, false));
      ordered.sort(Comparator.comparing(AttendanceRecord::scannedAt).thenComparing(AttendanceRecord::eventId));
    }
    Instant firstIn = null;
    Instant lastOut = null;
    Instant open = null;
    Instant lastEntry = null;
    Duration worked = Duration.ZERO;
    boolean duplicate = false;
    boolean review = false;
    boolean unresolved = false;
    for (var scan : ordered) {
      if ("DUPLICATE_SCAN".equals(scan.evidence())) { duplicate = true; continue; }
      if (scan.suspicious() || !scan.eligible() || !site.equals(scan.siteId())) {
        unresolved = true;
        continue;
      }
      review |= scan.hrReview();
      if ("ENTRY".equals(scan.direction())) {
        if (open != null) { duplicate = true; continue; }
        if (firstIn == null) firstIn = scan.scannedAt();
        lastEntry = scan.scannedAt();
        open = scan.scannedAt();
      } else if ("EXIT".equals(scan.direction()) && open != null && !scan.scannedAt().isBefore(open)) {
        lastOut = scan.scannedAt();
        worked = worked.plus(Duration.between(open, lastOut));
        open = null;
      } else {
        unresolved = true;
      }
    }
    Status status = open != null ? Status.MISSING_EXIT : duplicate ? Status.DUPLICATE
        : unresolved || firstIn == null ? Status.REVIEW : Status.COMPLETE;
    return Optional.of(new Calculation(new TimesheetRow(employeeId, StaffDirectory.name(employeeId), date, site,
        firstIn, lastOut, worked.toMinutes(), status, review || status != Status.COMPLETE), open, lastEntry));
  }
}
