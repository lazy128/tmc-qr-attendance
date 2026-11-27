package tmc.attendance.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.attendance.domain.AttendanceRecord;

public final class JdbcAttendanceStore implements AttendanceRecordStore {
  private final JdbcTemplate jdbc;

  public JdbcAttendanceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(AttendanceRecord record) {
    try {
      return jdbc.update(
          "INSERT INTO attendance_record("
              + "event_id, employee_id, site_id, station_id, direction, scanned_at, "
              + "command_state, passage_state, evidence, eligible, hr_review, suspicious"
              + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          record.eventId(),
          record.employeeId(),
          record.siteId(),
          record.stationId(),
          record.direction(),
          Timestamp.from(record.scannedAt()),
          record.command(),
          record.passage(),
          record.evidence(),
          record.eligible(),
          record.hrReview(),
          record.suspicious()
      ) == 1;
    } catch (DuplicateKeyException duplicate) {
      if (record.suspicious()) {
        jdbc.update("UPDATE attendance_record SET suspicious=TRUE WHERE event_id=?", record.eventId());
      }
      if ("CONFIRMED".equals(record.passage())) {
        jdbc.update("""
            UPDATE attendance_record SET passage_state=?, evidence=?, hr_review=?
            WHERE event_id=? AND passage_state <> 'CONFIRMED'
            """, record.passage(), record.evidence(), record.hrReview(), record.eventId());
      }
      return false;
    }
  }

  @Override
  public Optional<AttendanceRecord> find(String eventId) {
    return jdbc.query(
        "SELECT * FROM attendance_record WHERE event_id=?",
        rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
        eventId
    );
  }

  @Override
  public List<AttendanceRecord> all() {
    return jdbc.query("SELECT * FROM attendance_record ORDER BY scanned_at DESC", (rs, row) -> map(rs));
  }

  public List<AttendanceRecord> forEmployeeBetween(String employeeId, Instant start, Instant end) {
    return jdbc.query("""
        SELECT * FROM attendance_record WHERE employee_id=? AND scanned_at>=? AND scanned_at<?
        ORDER BY scanned_at,event_id
        """, (rs, row) -> map(rs), employeeId, Timestamp.from(start), Timestamp.from(end));
  }

  public List<AttendanceRecord> recentAcceptedForEmployee(String employeeId) {
    return jdbc.query("""
        SELECT * FROM attendance_record WHERE employee_id=? AND eligible=TRUE
        ORDER BY scanned_at DESC,event_id DESC LIMIT 10
        """, (rs, row) -> map(rs), employeeId);
  }

  private AttendanceRecord map(ResultSet rs) throws SQLException {
    return new AttendanceRecord(
        rs.getString("event_id"),
        rs.getString("employee_id"),
        rs.getString("site_id"),
        rs.getString("station_id"),
        rs.getString("direction"),
        rs.getTimestamp("scanned_at").toInstant(),
        rs.getString("command_state"),
        rs.getString("passage_state"),
        rs.getString("evidence"),
        rs.getBoolean("eligible"),
        rs.getBoolean("hr_review"),
        rs.getBoolean("suspicious")
    );
  }
}
