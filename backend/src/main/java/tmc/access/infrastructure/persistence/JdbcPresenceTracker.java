package tmc.access.infrastructure.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import tmc.access.domain.Direction;
import tmc.access.domain.port.PresenceTracker;
public final class JdbcPresenceTracker implements PresenceTracker {
 private final JdbcTemplate jdbc;
 private final boolean requireEntry;

 public JdbcPresenceTracker(JdbcTemplate jdbc) { this(jdbc, false); }
 public JdbcPresenceTracker(JdbcTemplate jdbc, boolean requireEntry) { this.jdbc = jdbc; this.requireEntry = requireEntry; }

 @Override public boolean allowed(String site, String employee, Direction next) {
   var current = jdbc.query("SELECT direction FROM employee_presence WHERE site_id=? AND employee_id=?",
       rs -> rs.next() ? rs.getString(1) : null, site, employee);
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
