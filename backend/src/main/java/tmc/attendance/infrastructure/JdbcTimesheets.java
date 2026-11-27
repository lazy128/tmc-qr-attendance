package tmc.attendance.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.attendance.domain.TimesheetRow;
import tmc.shared.DatabaseDialect;
import tmc.shared.StaffDirectory;

public final class JdbcTimesheets {
  public record Publication(String period, long revision, String status, Instant updatedAt) {}
  private final JdbcTemplate jdbc;

  private final boolean postgres;

  public JdbcTimesheets(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.postgres = DatabaseDialect.isPostgres(jdbc);
  }

  public void lock(String employeeId, LocalDate date) {
    if (postgres) {
      jdbc.update("""
          INSERT INTO timesheet_day_lock(employee_id,work_date) VALUES(?,?)
          ON CONFLICT (employee_id,work_date) DO NOTHING
          """, employeeId, date);
      jdbc.queryForObject("""
          SELECT 1 FROM timesheet_day_lock WHERE employee_id=? AND work_date=? FOR UPDATE
          """, Integer.class, employeeId, date);
    } else {
      jdbc.update("MERGE INTO timesheet_day_lock KEY(employee_id,work_date) VALUES(?,?)", employeeId, date);
    }
  }

  public boolean saveIfChanged(TimesheetRow row) {
    var saved = jdbc.query("SELECT s.*, e.full_name FROM daily_timesheet s LEFT JOIN workplace_employee e USING (employee_id) WHERE s.employee_id=? AND s.work_date=?",
        rs -> rs.next() ? readRow(rs) : null, row.employeeId(), row.workDate());
    if (row.equals(saved)) return false;
    jdbc.update(postgres ? """
        INSERT INTO daily_timesheet(employee_id,work_date,site_id,first_in,last_out,worked_minutes,status,review_flag,updated_at)
        VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        ON CONFLICT (employee_id,work_date) DO UPDATE SET site_id=EXCLUDED.site_id, first_in=EXCLUDED.first_in,
          last_out=EXCLUDED.last_out, worked_minutes=EXCLUDED.worked_minutes, status=EXCLUDED.status,
          review_flag=EXCLUDED.review_flag, updated_at=EXCLUDED.updated_at
        """ : """
        MERGE INTO daily_timesheet(employee_id,work_date,site_id,first_in,last_out,worked_minutes,status,review_flag,updated_at)
        KEY(employee_id,work_date) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """, row.employeeId(), row.workDate(), row.siteId(), offset(row.firstIn()), offset(row.lastOut()),
        row.workedMinutes(), row.status().name(), row.reviewFlag());
    markPending(YearMonth.from(row.workDate()));
    return true;
  }

  public List<TimesheetRow> month(YearMonth period) {
    return jdbc.query("SELECT s.*, e.full_name FROM daily_timesheet s LEFT JOIN workplace_employee e USING (employee_id) WHERE s.work_date>=? AND s.work_date<? ORDER BY s.work_date,s.employee_id",
        (rs, index) -> readRow(rs), period.atDay(1), period.plusMonths(1).atDay(1));
  }

  public Instant adjustment(String id, LocalDate date) {
    return jdbc.query("SELECT exit_at FROM timesheet_adjustment WHERE employee_id=? AND work_date=?",
        rs -> rs.next() ? instant(rs, "exit_at") : null, id, date);
  }

  public void addAdjustment(String id, LocalDate date, Instant exit, String note, String actor) {
    jdbc.update("""
        INSERT INTO timesheet_adjustment(employee_id,work_date,exit_at,justification,actor) VALUES(?,?,?,?,?)
        """, id, date, offset(exit), note, actor);
  }

  public void markPending(YearMonth month) {
    jdbc.update(postgres ? """
        INSERT INTO timesheet_publication(period,revision,status) VALUES(?,1,'PENDING')
        ON CONFLICT (period) DO UPDATE SET revision=timesheet_publication.revision+1,status='PENDING',next_attempt=NULL
        """ : """
        MERGE INTO timesheet_publication p USING (VALUES (CAST(? AS VARCHAR(7)))) v(period)
        ON p.period=v.period
        WHEN MATCHED THEN UPDATE SET revision=p.revision+1,status='PENDING',next_attempt=NULL
        WHEN NOT MATCHED THEN INSERT(period,revision,status) VALUES(v.period,1,'PENDING')
        """, month.toString());
  }

  public List<Publication> due(Instant now) {
    return jdbc.query("""
        SELECT * FROM timesheet_publication WHERE status IN ('PENDING','FAILED','RETRYING')
        AND (next_attempt IS NULL OR next_attempt<=?) ORDER BY period LIMIT 12
        """, (rs, i) -> readPublication(rs), offset(now));
  }

  public Optional<Publication> publication(YearMonth month) {
    return jdbc.query("SELECT * FROM timesheet_publication WHERE period=?",
        rs -> rs.next() ? Optional.of(readPublication(rs)) : Optional.empty(), month.toString());
  }

  public void publicationResult(Publication item, String status, Instant at, Instant next) {
    jdbc.update("""
        UPDATE timesheet_publication SET status=?,updated_at=COALESCE(?,updated_at),next_attempt=?
        WHERE period=? AND revision=?
        """, status, offset(at), offset(next), item.period(), item.revision());
  }

  private static TimesheetRow readRow(ResultSet rs) throws SQLException {
    String id = rs.getString("employee_id");
    var name = rs.getString("full_name");
    return new TimesheetRow(id, name == null ? StaffDirectory.name(id) : name, rs.getObject("work_date", LocalDate.class),
        rs.getString("site_id"), instant(rs, "first_in"), instant(rs, "last_out"), rs.getLong("worked_minutes"),
        TimesheetRow.Status.valueOf(rs.getString("status")), rs.getBoolean("review_flag"));
  }

  private static Publication readPublication(ResultSet rs) throws SQLException {
    return new Publication(rs.getString("period"), rs.getLong("revision"), rs.getString("status"), instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String field) throws SQLException {
    OffsetDateTime value = rs.getObject(field, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime offset(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
