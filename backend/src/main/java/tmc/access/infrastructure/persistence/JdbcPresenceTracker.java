package tmc.access.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.port.PresenceTracker;
import tmc.shared.ShiftCalendar;

public final class JdbcPresenceTracker implements PresenceTracker {
  private final JdbcTemplate jdbc;
  private final boolean requireEntry;
  private final ShiftCalendar calendar;
  private final int pairsPerDay;

  public JdbcPresenceTracker(JdbcTemplate jdbc) { this(jdbc, false); }

  public JdbcPresenceTracker(JdbcTemplate jdbc, boolean requireEntry) { this(jdbc, requireEntry, null, 1); }

  public JdbcPresenceTracker(JdbcTemplate jdbc, boolean requireEntry, ShiftCalendar calendar, int pairsPerDay) {
    if (pairsPerDay < 1) throw new IllegalArgumentException("tmc.attendance.pairs-per-day must be at least 1");
    this.jdbc = jdbc;
    this.requireEntry = requireEntry;
    this.calendar = calendar;
    this.pairsPerDay = pairsPerDay;
  }

  @Override
  public boolean allowed(String site, String employee, Direction next) {
    var current = jdbc.query("SELECT direction FROM employee_presence WHERE site_id=? AND employee_id=?",
        rs -> rs.next() ? rs.getString(1) : null, site, employee);
    return allows(current, next);
  }

  public boolean pendingCommand(String site, String employee) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM access_attempt
          WHERE site_id=? AND employee_id=? AND attendance_gate=TRUE AND command_state IN ('SENT','UNKNOWN'))
        """, Boolean.class, site, employee));
  }

  @Override
  public Reason denial(String site, String employee, Direction next, Instant scannedAt) {
    if (pendingCommand(site, employee)) return Reason.COMMAND_PENDING;
    return presenceDenial(site, employee, next, scannedAt);
  }

  Reason presenceDenial(String site, String employee, Direction next, Instant scannedAt) {
    if (calendar == null) return PresenceTracker.super.denial(site, employee, next, scannedAt);
    var date = calendar.workDateFor(employee, scannedAt);
    var presence = jdbc.query("""
        SELECT p.direction, p.evidence_quality, COALESCE(a.scanned_at,p.updated_at) AS recorded_at
        FROM employee_presence p LEFT JOIN access_attempt a ON a.event_id=p.event_id
        WHERE p.site_id=? AND p.employee_id=?
        """, rs -> rs.next() ? new DailyPresence(rs.getString(1), rs.getString(2),
        rs.getObject(3, OffsetDateTime.class).toInstant()) : null, site, employee);
    if (presence == null || !date.equals(calendar.workDateFor(employee, presence.recordedAt()))) {
      return allows(null, next) ? null : Reason.ANTI_PASSBACK;
    }
    if (next == Direction.ENTRY && Direction.EXIT.name().equals(presence.direction())
        && !"MANUAL_CORRECTION".equals(presence.evidence())) {
      if (pairsPerDay == 1) return Reason.SHIFT_COMPLETED;
      var range = calendar.searchRange(employee, date);
      long completed = jdbc.query("""
          SELECT scanned_at FROM access_attempt WHERE site_id=? AND employee_id=? AND attendance_gate=TRUE
          AND (command_state='ACKED' OR evidence='MANUAL_PASSAGE') AND direction='EXIT' AND scanned_at>=? AND scanned_at<?
          """, (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), site, employee,
          range.start().atOffset(ZoneOffset.UTC), range.endExclusive().atOffset(ZoneOffset.UTC))
          .stream().filter(time -> date.equals(calendar.workDateFor(employee, time))).count();
      if (completed >= pairsPerDay) return Reason.SHIFT_COMPLETED;
    }
    return allows(presence.direction(), next) ? null : Reason.ANTI_PASSBACK;
  }

  private record DailyPresence(String direction, String evidence, Instant recordedAt) {}

  private boolean allows(String current, Direction next) {
    return current == null ? !requireEntry || next == Direction.ENTRY : !current.equals(next.name());
  }

  @Override
  public void acknowledged(String site, String employee, Direction direction, String event) {
    storePresence(site, employee, direction, event, "ACK_ONLY");
  }

  void manuallyConfirmed(String site, String employee, Direction direction, String event) {
    storePresence(site, employee, direction, event, "MANUAL_PASSAGE");
  }

  private void storePresence(String site, String employee, Direction direction, String event, String evidence) {
    int updated = jdbc.update("""
        UPDATE employee_presence SET direction=?,event_id=?,evidence_quality=?,updated_at=CURRENT_TIMESTAMP
        WHERE site_id=? AND employee_id=?
        """, direction.name(), event, evidence, site, employee);
    if (updated == 0) {
      jdbc.update("INSERT INTO employee_presence(site_id,employee_id,direction,event_id,evidence_quality) VALUES(?,?,?,?,?)",
          site, employee, direction.name(), event, evidence);
    }
  }

  @Override
  public void confirmed(String site, String employee, Direction direction, String event) {
    jdbc.update("UPDATE employee_presence SET evidence_quality='PASSAGE_CONFIRMED' WHERE site_id=? AND employee_id=? AND event_id=?",
        site, employee, event);
  }
}
