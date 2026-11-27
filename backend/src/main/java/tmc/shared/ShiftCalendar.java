package tmc.shared;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

public record ShiftCalendar(ZoneId zone, LocalTime dayStarts, EmployeeDirectory employees,
    Map<String, Shift> shifts, Duration earlyGrace, Duration lateGrace) {
  public ShiftCalendar {
    shifts = Map.copyOf(shifts);
  }

  public record Shift(LocalTime start, LocalTime end) {}

  public record Range(Instant start, Instant endExclusive) {
    boolean includes(Instant time) {
      return !time.isBefore(start) && time.isBefore(endExclusive);
    }
  }

  public LocalDate workDateFor(String employeeId, Instant time) {
    return scheduledDate(shiftFor(employeeId), time).orElseGet(() -> fallbackDate(time));
  }

  public boolean withinAssignedShift(StaffDirectory.Employee employee, Instant time) {
    return scheduledDate(shifts.get(employee.shiftId()), time).isPresent();
  }

  public LocalDate fallbackDate(Instant time) {
    var local = time.atZone(zone);
    return local.toLocalTime().isBefore(dayStarts) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
  }

  public Instant dayStart(LocalDate date) {
    return date.atTime(dayStarts).atZone(zone).toInstant();
  }

  public Range searchRange(String employeeId, LocalDate date) {
    var start = dayStart(date);
    var end = dayStart(date.plusDays(1));
    var shift = shiftFor(employeeId);
    if (shift != null) {
      var window = occurrence(shift, date);
      if (window.start().isBefore(start)) start = window.start();
      if (window.endExclusive().isAfter(end)) end = window.endExclusive();
    }
    return new Range(start, end);
  }

  private Shift shiftFor(String id) {
    return employees.find(id).map(employee -> shifts.get(employee.shiftId())).orElse(null);
  }

  private Optional<LocalDate> scheduledDate(Shift shift, Instant time) {
    if (shift == null) return Optional.empty();
    var today = time.atZone(zone).toLocalDate();
    // In an overlap, prefer the most recent scheduled shift start.
    for (var date : new LocalDate[] {today.plusDays(1), today, today.minusDays(1)}) {
      if (occurrence(shift, date).includes(time)) return Optional.of(date);
    }
    return Optional.empty();
  }

  private Range occurrence(Shift shift, LocalDate date) {
    var endDate = shift.end().isAfter(shift.start()) ? date : date.plusDays(1);
    return new Range(date.atTime(shift.start()).atZone(zone).toInstant().minus(earlyGrace),
        endDate.atTime(shift.end()).atZone(zone).toInstant().plus(lateGrace).plusNanos(1));
  }
}
