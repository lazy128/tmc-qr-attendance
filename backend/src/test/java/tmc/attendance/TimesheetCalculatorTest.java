package tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import tmc.attendance.application.TimesheetCalculator;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.TimesheetRow.Status;

class TimesheetCalculatorTest {
  private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
  private final LocalDate day = LocalDate.of(2026, 6, 30);
  private final TimesheetCalculator clock = new TimesheetCalculator(zone, LocalTime.of(6, 0));

  @Test void pairsInOrderAndDoesNotCountTheBreak() {
    var afternoon = scan("3", "ENTRY", day, 13, 0);
    var result = clock.calculate("NV001", day, List.of(scan("4", "EXIT", day, 17, 15),
        scan("1", "ENTRY", day, 8, 0), afternoon, scan("2", "EXIT", day, 12, 0)), null).orElseThrow();
    assertThat(result.row().workedMinutes()).isEqualTo(495);
    assertThat(result.row().status()).isEqualTo(Status.COMPLETE);
    assertThat(result.lastEntry()).isEqualTo(afternoon.scannedAt());
  }

  @Test void flagsMissingExitAndDuplicatesWithoutCountingEitherAsTime() {
    var in = scan("1", "ENTRY", day, 8, 0);
    var duplicate = scan("2", "ENTRY", day, 8, 2);
    var missing = clock.calculate("NV001", day, List.of(in, duplicate), null).orElseThrow();
    assertThat(missing.row().status()).isEqualTo(Status.MISSING_EXIT);
    assertThat(missing.row().workedMinutes()).isZero();
    assertThat(missing.unpairedEntry()).isEqualTo(in.scannedAt());
    var corrected = clock.calculate("NV001", day, List.of(in, duplicate),
        scan("fix", "EXIT", day, 17, 0).scannedAt()).orElseThrow();
    assertThat(corrected.row().status()).isEqualTo(Status.DUPLICATE);
    assertThat(corrected.row().workedMinutes()).isEqualTo(540);
  }

  @Test void attributesAfterMidnightExitToPreviousWorkDateEvenAcrossMonths() {
    var out = scan("2", "EXIT", day.plusDays(1), 5, 15);
    assertThat(clock.workDate(out.scannedAt())).isEqualTo(day);
    var row = clock.calculate("NV001", day, List.of(scan("1", "ENTRY", day, 22, 0), out), null)
        .orElseThrow().row();
    assertThat(row.workedMinutes()).isEqualTo(435);
    assertThat(row.status()).isEqualTo(Status.COMPLETE);
  }

  private AttendanceRecord scan(String id, String direction, LocalDate date, int hour, int minute) {
    return new AttendanceRecord(id, "NV001", "SITE-BD", "GATE", direction,
        date.atTime(hour, minute).atZone(zone).toInstant(), "ACKED", "CONFIRMED", null, true, false, false);
  }
}
