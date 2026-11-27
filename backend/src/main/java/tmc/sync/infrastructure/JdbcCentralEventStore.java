package tmc.sync.infrastructure;

import java.util.function.Supplier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tmc.access.domain.AccessAttempt;
import tmc.sync.application.CentralEventStore;

public final class JdbcCentralEventStore implements CentralEventStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public JdbcCentralEventStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
  }

  @Override public <T> T locked(Supplier<T> work) {
    return tx.execute(status -> {
      jdbc.queryForObject("SELECT id FROM central_sync_lock WHERE id=1 FOR UPDATE", Integer.class);
      return work.get();
    });
  }

  @Override public AccessAttempt find(String eventId) {
    return jdbc.query("SELECT payload FROM central_access_event WHERE event_id=?",
        row -> row.next() ? decode(row.getString(1)) : null, eventId);
  }

  @Override public String firstSite(String jti) {
    return jdbc.query("SELECT site_id FROM central_token_use WHERE jti=?",
        row -> row.next() ? row.getString(1) : null, jti);
  }

  @Override public void save(AccessAttempt attempt, boolean newEvent, boolean newToken) {
    if (newToken) jdbc.update("INSERT INTO central_token_use(jti,site_id) VALUES(?,?)",
        attempt.jti(), attempt.siteId().value());
    String payload = encode(attempt);
    if (newEvent) jdbc.update("INSERT INTO central_access_event(event_id,payload) VALUES(?,?)",
        attempt.eventId().value(), payload);
    else jdbc.update("UPDATE central_access_event SET payload=? WHERE event_id=?", payload, attempt.eventId().value());
  }

  private String encode(AccessAttempt attempt) {
    try {
      return json.writeValueAsString(attempt);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize Central access event", error);
    }
  }

  private AccessAttempt decode(String payload) {
    try {
      return json.readValue(payload, AccessAttempt.class);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot read Central access event", error);
    }
  }
}
