package tmc.sync.application;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tmc.sync.infrastructure.JdbcCentralEventStore;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;
import static org.assertj.core.api.Assertions.assertThat;

class CentralSyncReceiverTest {
  private AccessAttempt attempt(String eventId, String siteId) {
    return new AccessAttempt(
        new EventId(eventId),
        "c",
        "emp",
        new SiteId(siteId),
        new StationId("g"),
        Direction.ENTRY,
        Instant.EPOCH,
        Instant.EPOCH,
        "cred",
        1,
        "shared-jti",
        Decision.ALLOW,
        null,
        Command.ACKED,
        Passage.UNCONFIRMED,
        false,
        Evidence.ACK_ONLY,
        false
    );
  }

  @Test
  void duplicateEventIsIdempotentAndCrossSiteJtiSuspicious() {
    var database = new DriverManagerDataSource("jdbc:h2:mem:central_restart;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(database);
    var jdbc = new JdbcTemplate(database);
    var tx = new DataSourceTransactionManager(database);
    var json = new ObjectMapper().findAndRegisterModules();
    var receiver = new CentralSyncReceiver(new JdbcCentralEventStore(jdbc, tx, json));
    assertThat(receiver.receive(attempt("e1", "s1")).suspicious()).isFalse();

    var restarted = new CentralSyncReceiver(new JdbcCentralEventStore(new JdbcTemplate(database), tx, json));
    assertThat(restarted.receive(attempt("e1", "s1")).duplicate()).isTrue();
    assertThat(restarted.receive(attempt("e2", "s2")).suspicious()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM central_access_event", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM central_token_use", Integer.class)).isEqualTo(1);
  }
}
