package tmc.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.infrastructure.persistence.JdbcPresenceTracker;
import tmc.attendance.application.TimesheetCalculator;
import tmc.shared.ShiftCalendar;
import tmc.shared.ShiftCalendar.Shift;
import tmc.shared.StaffDirectory;

class ShiftCalendarTest {
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 2);
  private final ShiftCalendar calendar = new ShiftCalendar(ZONE, LocalTime.of(6, 0), StaffDirectory::find,
      Map.of("STANDARD", new Shift(LocalTime.of(6, 0), LocalTime.of(14, 0)),
          "NIGHT", new Shift(LocalTime.of(22, 0), LocalTime.of(6, 0))), Duration.ofMinutes(10), Duration.ofMinutes(30));

  @Test
  void allowsAnEarlyEntryAfterYesterdayWasCompleted() {
    var jdbc = database();
    scan(jdbc, "NV001", Direction.ENTRY, at(TODAY.minusDays(1), "06:05"), "previous-in");
    scan(jdbc, "NV001", Direction.EXIT, at(TODAY.minusDays(1), "14:05"), "previous-out");
    assertThat(tracker(jdbc, 1).denial("SITE-BD", "NV001", Direction.ENTRY, at(TODAY, "05:50"))).isNull();
  }

  @Test
  void acceptsLateNightShiftExitAndTheNextEntryOnTheSameCivilDate() {
    var jdbc = database();
    var presence = tracker(jdbc, 1);
    scan(jdbc, "NV006", Direction.ENTRY, at(TODAY.minusDays(1), "22:00"), "night-in");
    assertThat(presence.denial("SITE-LA", "NV006", Direction.EXIT, at(TODAY, "06:20"))).isNull();
    scan(jdbc, "NV006", Direction.EXIT, at(TODAY, "06:20"), "night-out");
    assertThat(presence.denial("SITE-LA", "NV006", Direction.ENTRY, at(TODAY, "22:00"))).isNull();
  }

  @Test
  void permitsTheConfiguredNumberOfPairsAndKeepsManualOverride() {
    var jdbc = database();
    scan(jdbc, "NV001", Direction.ENTRY, at(TODAY, "06:10"), "one-in");
    scan(jdbc, "NV001", Direction.EXIT, at(TODAY, "09:00"), "one-out");
    assertThat(tracker(jdbc, 1).denial("SITE-BD", "NV001", Direction.ENTRY, at(TODAY, "09:30")))
        .isEqualTo(Reason.SHIFT_COMPLETED);
    assertThat(tracker(jdbc, 2).denial("SITE-BD", "NV001", Direction.ENTRY, at(TODAY, "09:30"))).isNull();
    scan(jdbc, "NV001", Direction.ENTRY, at(TODAY, "09:30"), "two-in");
    scan(jdbc, "NV001", Direction.EXIT, at(TODAY, "11:00"), "two-out");
    assertThat(tracker(jdbc, 2).denial("SITE-BD", "NV001", Direction.ENTRY, at(TODAY, "12:00")))
        .isEqualTo(Reason.SHIFT_COMPLETED);
    jdbc.update("UPDATE employee_presence SET evidence_quality='MANUAL_CORRECTION' WHERE employee_id='NV001'");
    assertThat(tracker(jdbc, 2).denial("SITE-BD", "NV001", Direction.ENTRY, at(TODAY, "12:00"))).isNull();
  }

  @ParameterizedTest
  @CsvSource({
      "NV001, 05:49, -1", "NV001, 05:50, 0", "NV001, 16:00, 0",
      "NV006, 06:20, -1", "NV006, 06:30, -1", "NV006, 06:31, 0", "NV006, 22:00, 0"
  })
  void timesheetsAndPresenceUseTheSameDateIncludingOutsideTheShift(String id, String time, int offset) {
    var jdbc = database();
    var calculator = new TimesheetCalculator(calendar, StaffDirectory::find);
    var instant = at(TODAY, time);
    var date = TODAY.plusDays(offset);
    assertThat(calculator.workDateFor(id, instant)).isEqualTo(date);
    String site = StaffDirectory.find(id).orElseThrow().siteId();
    var previous = date.equals(TODAY) ? TODAY.minusDays(1) : TODAY.minusDays(2);
    var anchor = id.equals("NV001") ? "09:00" : "23:00";
    scan(jdbc, id, Direction.EXIT, at(previous, anchor), "earlier");
    assertThat(tracker(jdbc, 1).denial(site, id, Direction.ENTRY, instant)).isNull();
    scan(jdbc, id, Direction.EXIT, at(date, anchor), "same-date");
    assertThat(tracker(jdbc, 1).denial(site, id, Direction.ENTRY, instant)).isEqualTo(Reason.SHIFT_COMPLETED);
  }

  private JdbcPresenceTracker tracker(JdbcTemplate jdbc, int pairs) {
    return new JdbcPresenceTracker(jdbc, true, calendar, pairs);
  }

  private JdbcTemplate database() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    return new JdbcTemplate(source);
  }

  private void scan(JdbcTemplate jdbc, String id, Direction direction, Instant time, String event) {
    String site = StaffDirectory.find(id).orElseThrow().siteId();
    jdbc.update("""
        INSERT INTO access_attempt(event_id,site_id,employee_id,direction,scanned_at,command_state)
        VALUES (?,?,?,?,?,'ACKED')
        """, event, site, id, direction.name(), time.atOffset(ZoneOffset.UTC));
    tracker(jdbc, 1).acknowledged(site, id, direction, event);
  }

  private Instant at(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
  }
}
