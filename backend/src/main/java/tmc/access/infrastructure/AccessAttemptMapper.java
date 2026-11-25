package tmc.access.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.Direction;
import tmc.access.domain.EventId;
import tmc.access.domain.SiteId;
import tmc.access.domain.StationId;

final class AccessAttemptMapper {
  private AccessAttemptMapper() {}

  static AccessAttempt fromRow(ResultSet rs) throws SQLException {
    String reason = rs.getString("reason_code");
    String evidence = rs.getString("evidence");
    return new AccessAttempt(
        new EventId(rs.getString("event_id")),
        rs.getString("correlation_id"),
        rs.getString("employee_id"),
        new SiteId(rs.getString("site_id")),
        new StationId(rs.getString("station_id")),
        Direction.valueOf(rs.getString("direction")),
        instant(rs.getTimestamp("scanned_at")),
        instant(rs.getTimestamp("received_at")),
        rs.getString("credential_id"),
        rs.getInt("credential_version"),
        rs.getString("jti"),
        Decision.valueOf(rs.getString("decision")),
        reason == null ? null : Reason.valueOf(reason),
        Command.valueOf(rs.getString("command_state")),
        Passage.valueOf(rs.getString("passage_state")),
        rs.getBoolean("offline_accepted"),
        evidence == null ? null : Evidence.valueOf(evidence),
        rs.getBoolean("suspicious")
    );
  }

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
