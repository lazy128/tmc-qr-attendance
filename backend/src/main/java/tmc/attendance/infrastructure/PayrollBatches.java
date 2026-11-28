package tmc.attendance.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tmc.attendance.application.AttendanceCsv;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.TimesheetRow;
import tmc.shared.audit.AuditWriter;

@Repository
public class PayrollBatches {
  private final JdbcTemplate jdbc;
  private final JdbcTimesheets sheets;
  private final ObjectMapper json;
  private final AuditWriter audit;
  private final ZoneId zone;

  public PayrollBatches(JdbcTemplate jdbc, JdbcTimesheets sheets, ObjectMapper json,
      AuditWriter audit, TimesheetCalculator calculator) {
    this.jdbc = jdbc;
    this.sheets = sheets;
    this.json = json;
    this.audit = audit;
    this.zone = calculator.zone();
  }

  public History history(YearMonth period) {
    var latest = latest(period);
    int changes = changes(latest, snapshot(period)).size();
    return new History(changes, jdbc.query("""
        SELECT batch_no, row_count, created_at FROM payroll_batch WHERE period=? ORDER BY batch_no DESC
        """, (row, index) -> info(row), period.toString()));
  }

  @Transactional
  public Optional<Batch> capture(YearMonth period, String actor, String correlation, Instant now) {
    jdbc.queryForObject("SELECT id FROM payroll_batch_lock WHERE id=1 FOR UPDATE", Integer.class);
    var latest = latest(period);
    var current = snapshot(period);
    var changes = changes(latest, current);
    if (changes.isEmpty()) return Optional.ofNullable(latest).map(Stored::batch);
    int next = latest == null ? 1 : Math.incrementExact(latest.batch().number());
    var batch = new Batch(next, changes.size(), now);
    jdbc.update("""
        INSERT INTO payroll_batch(period,batch_no,row_count,created_at,actor,csv_payload,snapshot_payload)
        VALUES (?,?,?,?,?,?,?)
        """, period.toString(), batch.number(), batch.rows(), now.atOffset(ZoneOffset.UTC), actor,
        render(period, batch, changes), encode(current));
    audit.append(actor, "PAYROLL_BATCH_CREATED", period + "/" + next, "SUCCESS", correlation, now);
    return Optional.of(batch);
  }

  public Optional<String> csv(YearMonth period, int number) {
    return jdbc.query("SELECT csv_payload FROM payroll_batch WHERE period=? AND batch_no=?",
        row -> row.next() ? Optional.of(row.getString(1)) : Optional.empty(), period.toString(), number);
  }

  private Stored latest(YearMonth period) {
    return jdbc.query("""
        SELECT batch_no,row_count,created_at,snapshot_payload FROM payroll_batch
        WHERE period=? ORDER BY batch_no DESC LIMIT 1
        """, row -> row.next() ? new Stored(info(row), decode(row.getString("snapshot_payload"))) : null,
        period.toString());
  }

  private List<TimesheetRow> snapshot(YearMonth period) {
    return sheets.month(period).stream().filter(row -> row.firstIn() != null).toList();
  }

  private static List<Change> changes(Stored latest, List<TimesheetRow> current) {
    Map<RowKey, TimesheetRow> before = new LinkedHashMap<>();
    if (latest != null) {
      for (var row : latest.snapshot()) before.put(RowKey.of(row), row);
    }
    var changes = new ArrayList<Change>();
    for (var row : current) {
      var old = before.remove(RowKey.of(row));
      if (!row.equals(old)) changes.add(new Change("UPSERT", row));
    }
    before.values().forEach(row -> changes.add(new Change("VOID", row)));
    return changes;
  }

  private String render(YearMonth period, Batch batch, List<Change> changes) {
    var csv = new StringBuilder("\uFEFFperiod,batch,operation,employee_id,employee_name,work_date,site_id,"
        + "first_in,last_out,worked_minutes,status,hr_review\r\n");
    for (var change : changes) {
      var row = change.row();
      boolean voided = "VOID".equals(change.operation());
      csv.append(period).append(',').append(batch.number()).append(',').append(change.operation()).append(',')
          .append(AttendanceCsv.field(row.employeeId())).append(',').append(AttendanceCsv.field(row.employeeName()))
          .append(',').append(row.workDate()).append(',').append(AttendanceCsv.field(row.siteId())).append(',')
          .append(voided ? "" : timestamp(row.firstIn())).append(',').append(voided ? "" : timestamp(row.lastOut()))
          .append(',').append(voided ? 0 : row.workedMinutes()).append(',')
          .append(voided ? "" : row.status().name()).append(',')
          .append(voided ? "" : Boolean.toString(row.reviewFlag())).append("\r\n");
    }
    return csv.toString();
  }

  private String timestamp(Instant time) {
    return time == null ? "" : time.atZone(zone).toOffsetDateTime().toString();
  }

  private static Batch info(ResultSet row) throws SQLException {
    return new Batch(row.getInt("batch_no"), row.getInt("row_count"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private String encode(List<TimesheetRow> rows) {
    try {
      return json.writeValueAsString(rows);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Không thể lưu dữ liệu lô lương.", failure);
    }
  }

  private List<TimesheetRow> decode(String serialized) {
    try {
      return json.readValue(serialized, new TypeReference<List<TimesheetRow>>() {});
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Không thể đọc dữ liệu lô lương.", failure);
    }
  }

  private record RowKey(String employeeId, LocalDate date) {
    static RowKey of(TimesheetRow row) { return new RowKey(row.employeeId(), row.workDate()); }
  }
  private record Stored(Batch batch, List<TimesheetRow> snapshot) {}
  private record Change(String operation, TimesheetRow row) {}
  public record Batch(int number, int rows, Instant createdAt) {}
  public record History(int pendingRows, List<Batch> batches) {}
}
