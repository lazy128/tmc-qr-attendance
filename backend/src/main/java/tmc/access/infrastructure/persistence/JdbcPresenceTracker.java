package tmc.access.infrastructure.persistence;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.port.PresenceTracker;
public final class JdbcPresenceTracker implements PresenceTracker {
 private final JdbcTemplate jdbc;
 private final boolean requireEntry;
 private final UnaryOperator<Instant> workDayStart;

 public JdbcPresenceTracker(JdbcTemplate jdbc) { this(jdbc, false); }
 public JdbcPresenceTracker(JdbcTemplate jdbc, boolean requireEntry) { this(jdbc, requireEntry, null); }
 public JdbcPresenceTracker(JdbcTemplate jdbc, boolean requireEntry, UnaryOperator<Instant> workDayStart) {
   this.jdbc = jdbc;
   this.requireEntry = requireEntry;
   this.workDayStart = workDayStart;
 }

 @Override public boolean allowed(String site, String employee, Direction next) {
   var current = jdbc.query("SELECT direction FROM employee_presence WHERE site_id=? AND employee_id=?",
       rs -> rs.next() ? rs.getString(1) : null, site, employee);
   return allows(current, next);
 }
 @Override public Reason denial(String site, String employee, Direction next, Instant scannedAt) {
   if (workDayStart == null) return PresenceTracker.super.denial(site, employee, next, scannedAt);
   var boundary = OffsetDateTime.ofInstant(workDayStart.apply(scannedAt), ZoneOffset.UTC);
   var presence = jdbc.query("""
       SELECT p.direction, p.evidence_quality FROM employee_presence p
       LEFT JOIN access_attempt a ON a.event_id=p.event_id
       WHERE p.site_id=? AND p.employee_id=? AND COALESCE(a.scanned_at,p.updated_at)>=?
       """, rs -> rs.next() ? new DailyPresence(rs.getString(1), rs.getString(2)) : null,
       site, employee, boundary);
   if (presence != null && Direction.EXIT.name().equals(presence.direction())
       && !"MANUAL_CORRECTION".equals(presence.evidence())) return Reason.SHIFT_COMPLETED;
   return allows(presence == null ? null : presence.direction(), next) ? null : Reason.ANTI_PASSBACK;
 }
 private record DailyPresence(String direction, String evidence) {}
 private boolean allows(String current, Direction next) {
   return current == null ? !requireEntry || next == Direction.ENTRY : !current.equals(next.name());
 }
 @Override public void acknowledged(String site,String employee,Direction direction,String event){store(site,employee,direction,event,"ACK_ONLY");}
 @Override public void confirmed(String site,String employee,Direction direction,String event) {
   jdbc.update(
       "UPDATE employee_presence SET evidence_quality='PASSAGE_CONFIRMED' WHERE site_id=? AND employee_id=? AND event_id=?",
       site, employee, event
   );
 }
 private void store(String site,String employee,Direction direction,String event,String quality){int n=jdbc.update("UPDATE employee_presence SET direction=?,event_id=?,evidence_quality=?,updated_at=CURRENT_TIMESTAMP WHERE site_id=? AND employee_id=?",direction.name(),event,quality,site,employee);if(n==0)jdbc.update("INSERT INTO employee_presence(site_id,employee_id,direction,event_id,evidence_quality) VALUES(?,?,?,?,?)",site,employee,direction.name(),event,quality);}
}
