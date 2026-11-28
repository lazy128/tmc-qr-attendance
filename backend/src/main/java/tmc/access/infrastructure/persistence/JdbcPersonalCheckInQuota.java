package tmc.access.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.access.domain.PersonalSelfLimitException;
import tmc.shared.ShiftCalendar;

public final class JdbcPersonalCheckInQuota {
  private final JdbcTemplate jdbc;
  private final ShiftCalendar calendar;
  private final int limit;

  public JdbcPersonalCheckInQuota(JdbcTemplate jdbc, ShiftCalendar calendar, int limit) {
    if (limit < 1) throw new IllegalArgumentException("tmc.checkin.personal-self.max-per-day must be positive");
    this.jdbc = jdbc;
    this.calendar = calendar;
    this.limit = limit;
  }

  // The caller holds the employee's credential lock through the attempt insert.
  void requireAvailable(String employeeId, Instant scannedAt) {
    var workDate = calendar.workDateFor(employeeId, scannedAt);
    var range = calendar.searchRange(employeeId, workDate);
    long used = jdbc.query("""
        SELECT scanned_at FROM access_attempt
        WHERE channel='PERSONAL_SELF' AND decision='ALLOW' AND evidence='SELF_REPORTED'
          AND employee_id=? AND scanned_at>=? AND scanned_at<?
        """, (row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(), employeeId,
        range.start().atOffset(ZoneOffset.UTC), range.endExclusive().atOffset(ZoneOffset.UTC))
        .stream().filter(time -> workDate.equals(calendar.workDateFor(employeeId, time))).limit(limit).count();
    if (used >= limit) throw new PersonalSelfLimitException();
  }
}
