package tmc.attendance.application;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import tmc.attendance.domain.AttendanceRecord;

public final class AttendanceCsv {
  private AttendanceCsv() {}

  public static String render(List<AttendanceRecord> records, YearMonth month, ZoneId zone) {
    StringBuilder csv = new StringBuilder(
        "event_id,employee_id,site_id,direction,scanned_at,evidence,hr_review\r\n"
    );
    for (AttendanceRecord row : records) {
      if (!row.eligible() || !"ACKED".equals(row.command()) || row.suspicious()) continue;
      if (!month.equals(YearMonth.from(row.scannedAt().atZone(zone)))) continue;
      csv.append(field(row.eventId())).append(',')
          .append(field(row.employeeId())).append(',')
          .append(field(row.siteId())).append(',')
          .append(field(row.direction())).append(',')
          .append(row.scannedAt()).append(',')
          .append(field(row.evidence())).append(',')
          .append(row.hrReview()).append("\r\n");
    }
    return csv.toString();
  }

  private static String field(String value) {
    if (value == null) return "";
    String trimmed = value.stripLeading();
    if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")
        || (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)) {
      value = "'" + value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
