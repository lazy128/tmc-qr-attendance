package tmc.access.infrastructure.persistence;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.CredentialChangedException;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.AccessStates.Channel;
import tmc.access.domain.AccessStates.Evidence;
import tmc.access.domain.AntiPassbackException;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.ReplayDetectedException;
import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.IdempotencyEntry;
import tmc.access.domain.port.IdempotencyStore;
import tmc.access.domain.port.PresenceCorrectionStore;
import tmc.access.domain.port.UsedTokenStore;
import tmc.shared.audit.AuditWriter;
import tmc.shared.audit.AuditHash;
import tmc.shared.outbox.CentralOutbox;

public final class JdbcAttemptStore
    implements AttemptStore, IdempotencyStore, UsedTokenStore, AuditWriter, PresenceCorrectionStore, CentralOutbox {
  private static final String INSERT_ATTEMPT = """
      INSERT INTO access_attempt(
        event_id, correlation_id, employee_id, site_id, station_id, direction, scanned_at, received_at,
        credential_id, credential_version, jti, decision, reason_code, command_state, passage_state,
        offline_accepted, evidence, suspicious, attendance_gate, channel
      ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Duration reservationTtl;
  private final JdbcPresenceTracker presence;
  private final JdbcPersonalCheckInQuota personalQuota;
  private final EdgeOutboxCodec outboxCodec = new EdgeOutboxCodec();

  public JdbcAttemptStore(
      JdbcTemplate jdbc, PlatformTransactionManager manager, Duration reservationTtl, JdbcPresenceTracker presence,
      JdbcPersonalCheckInQuota personalQuota
  ) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
    this.reservationTtl = reservationTtl;
    this.presence = presence;
    this.personalQuota = personalQuota;
  }

  @Override
  public void persistPrepared(AccessAttempt attempt, String jti) {
    try {
      tx.executeWithoutResult(status -> {
        var conflict = jdbc.query("SELECT active, version FROM credential WHERE credential_id=? FOR UPDATE", row -> {
          if (!row.next() || !row.getBoolean("active")) return Reason.REVOKED;
          return row.getInt("version") != attempt.credentialVersion() ? Reason.CVER_MISMATCH : null;
        }, attempt.credentialId());
        if (conflict != null) throw new CredentialChangedException(conflict);
        try {
          jdbc.update(
              "INSERT INTO used_token(site_id, jti, event_id) VALUES(?,?,?)",
              attempt.siteId().value(),
              jti,
              attempt.eventId().value()
          );
        } catch (DuplicateKeyException duplicate) {
          throw new ReplayDetectedException();
        }
        boolean selfReported = attempt.channel() == Channel.PERSONAL_SELF;
        if (selfReported) {
          personalQuota.requireAvailable(attempt.employeeId(), attempt.scannedAt());
        }
        if (attempt.attendanceGate() && !selfReported) {
          if (presence.pendingCommand(attempt.siteId().value(), attempt.employeeId())) {
            throw new AntiPassbackException(Reason.COMMAND_PENDING);
          }
          var denial = presence.presenceDenial(attempt.siteId().value(), attempt.employeeId(),
              attempt.direction(), attempt.scannedAt());
          if (denial != null) throw new AntiPassbackException(denial);
        }
        insertAttempt(attempt, jti);
        jdbc.update(
            "INSERT INTO edge_outbox(event_id, payload, status, attendance_status) VALUES(?,?, 'PENDING',?)",
            attempt.eventId().value(),
            outboxCodec.encode(attempt), attempt.attendanceGate() ? "PENDING" : "SKIPPED"
        );
        if (selfReported) complete(attempt.eventId().value(), attempt);
        appendAuditRow("station", "ACCESS_ALLOW", attempt.eventId().value(), "ALLOW", attempt.correlationId(), attempt.receivedAt());
      });
    } catch (ReplayDetectedException replay) {
      throw replay;
    }
  }

  @Override
  public void persistDenied(AccessAttempt attempt) {
    tx.executeWithoutResult(status -> {
      insertAttempt(attempt, null);
      if (attempt.duplicateScan()) {
        jdbc.update("INSERT INTO edge_outbox(event_id,payload,status) VALUES(?,?,'PENDING')",
            attempt.eventId().value(), outboxCodec.encode(attempt));
      }
      appendAuditRow(
          "station",
          "ACCESS_DENY",
          attempt.eventId().value(),
          attempt.reasonCode().name(),
          attempt.correlationId(),
          attempt.receivedAt()
      );
      complete(attempt.eventId().value(), attempt);
    });
  }

  @Override
  public void auditRejection(AccessAttempt attempt) {
    append("station", "ACCESS_DENY", attempt.eventId().value(), attempt.reasonCode().name(),
        attempt.correlationId(), attempt.receivedAt());
  }

  @Override
  public void update(AccessAttempt attempt) {
    tx.executeWithoutResult(status -> {
      var current = lockAttempt(attempt.eventId().value());
      if (current.command() != Command.EXPIRED && attempt.passage() == Passage.CONFIRMED
          && current.passage() != Passage.CONFIRMED) {
        writeOutcome(current.withPassage());
        if (current.attendanceGate()) {
          presence.confirmed(current.siteId().value(), current.employeeId(), current.direction(), current.eventId().value());
        }
      }
    });
  }

  @Override
  public void recordOutcome(AccessAttempt attempt) {
    recordOutcome(attempt, null);
  }

  public void expireCommand(AccessAttempt attempt, Instant now) {
    recordOutcome(attempt.withCommand(Command.EXPIRED), now);
  }

  private void recordOutcome(AccessAttempt attempt, Instant expiredAt) {
    tx.executeWithoutResult(status -> {
      var current = lockAttempt(attempt.eventId().value());
      if (current.command() == attempt.command()
          || current.command() == Command.ACKED || current.command() == Command.FAILED
          || current.command() == Command.EXPIRED) return;
      var next = current.withCommand(attempt.command());
      if (next.command() == Command.ACKED && next.attendanceGate()) {
        presence.acknowledged(next.siteId().value(), next.employeeId(), next.direction(), next.eventId().value());
        if (next.passage() == Passage.CONFIRMED) {
          presence.confirmed(next.siteId().value(), next.employeeId(), next.direction(), next.eventId().value());
        }
      }
      writeOutcome(next);
      complete(next.eventId().value(), next);
      if (expiredAt != null) {
        appendAuditRow("system", "GATE_COMMAND_EXPIRED", next.eventId().value(), "REVIEW_PENDING",
            next.correlationId(), expiredAt);
      }
    });
  }

  private AccessAttempt lockAttempt(String eventId) {
    return jdbc.query(
        "SELECT * FROM access_attempt WHERE event_id=? FOR UPDATE",
        rs -> {
          if (!rs.next()) throw new IllegalStateException("attempt not found");
          return AccessAttemptMapper.fromRow(rs);
        }, eventId
    );
  }

  private void writeOutcome(AccessAttempt attempt) {
    int updated = jdbc.update(
        "UPDATE access_attempt SET command_state=?, passage_state=?, evidence=?, suspicious=? WHERE event_id=?",
        attempt.command().name(),
        attempt.passage().name(),
        attempt.evidence() == null ? null : attempt.evidence().name(),
        attempt.suspicious(),
        attempt.eventId().value()
    );
    if (updated != 1) {
      throw new IllegalStateException("attempt not found");
    }
    jdbc.update(
        "UPDATE edge_outbox SET payload=?, central_status='PENDING', attendance_status="
            + "CASE WHEN ? THEN 'PENDING' WHEN ? OR attendance_status='SKIPPED' THEN 'SKIPPED' ELSE 'PENDING' END WHERE event_id=?",
        outboxCodec.encode(attempt), attempt.evidence() == Evidence.MANUAL_PASSAGE && attempt.attendanceGate(),
        attempt.command() == Command.EXPIRED, attempt.eventId().value()
    );
  }

  @Override
  public Optional<AccessAttempt> findAttempt(String eventId) {
    return jdbc.query(
        "SELECT * FROM access_attempt WHERE event_id=?",
        rs -> rs.next() ? Optional.of(AccessAttemptMapper.fromRow(rs)) : Optional.empty(),
        eventId
    );
  }

  @Override
  public boolean consume(String siteId, String jti, String eventId) {
    try {
      return jdbc.update("INSERT INTO used_token(site_id, jti, event_id) VALUES(?,?,?)", siteId, jti, eventId) == 1;
    } catch (Exception duplicate) {
      return false;
    }
  }

  @Override
  public boolean alreadyConsumed(String siteId, String jti) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM used_token WHERE site_id=? AND jti=?)", Boolean.class, siteId, jti
    ));
  }

  @Override
  public void append(String actor, String action, String target, String result, String correlation, Instant time) {
    tx.executeWithoutResult(status -> appendAuditRow(actor, action, target, result, correlation, time));
  }

  @Override
  public boolean correctMissedExit(
      String expectedEventId, String site, String station, String actor, String correlation, Instant now
  ) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      var current = findAttempt(expectedEventId).orElse(null);
      if (current == null || !site.equals(current.siteId().value()) || !station.equals(current.stationId().value())) {
        return false;
      }
      int changed = jdbc.update("""
          UPDATE employee_presence SET direction='EXIT', evidence_quality='MANUAL_CORRECTION',
            event_id=?, updated_at=CURRENT_TIMESTAMP
          WHERE site_id=? AND employee_id=? AND event_id=? AND direction='ENTRY'
          """, UUID.randomUUID().toString(), site, current.employeeId(), expectedEventId);
      if (changed == 0) return false;
      appendAuditRow(actor, "PRESENCE_CORRECTION", expectedEventId, "MISSED_EXIT", correlation, now);
      return true;
    }));
  }

  public List<AccessAttempt> pendingAttendance(int limit) {
    return jdbc.query(
            "SELECT o.event_id FROM edge_outbox o JOIN access_attempt a ON a.event_id=o.event_id "
                + "WHERE o.attendance_status='PENDING' AND a.attendance_gate=TRUE "
                + "AND (a.command_state='ACKED' OR a.evidence IN ('SELF_REPORTED','MANUAL_PASSAGE') OR a.reason_code IN ('REPLAY','ANTI_PASSBACK')) "
                + "ORDER BY o.created_at LIMIT ?",
            (rs, row) -> rs.getString(1),
            limit
        ).stream()
        .map(id -> findAttempt(id).orElse(null))
        .filter(Objects::nonNull)
        .toList();
  }

  public long pendingOutboxDepth(boolean centralEnabled) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM edge_outbox o JOIN access_attempt a ON a.event_id=o.event_id
        WHERE (o.attendance_status='PENDING' AND (a.command_state IN ('SENT','UNKNOWN','ACKED') OR a.evidence IN ('SELF_REPORTED','MANUAL_PASSAGE')))
           OR (? AND o.central_status='PENDING')
        """, Long.class, centralEnabled);
  }

  public List<AccessAttempt> pendingCommands(Instant sentBefore, int limit) {
    return jdbc.query("""
        SELECT * FROM access_attempt
        WHERE received_at IS NOT NULL AND (command_state='UNKNOWN' OR (command_state='SENT' AND received_at<=?))
        ORDER BY received_at,event_id LIMIT ?
        """, (rs, row) -> AccessAttemptMapper.fromRow(rs), AccessAttemptMapper.timestamp(sentBefore), limit);
  }

  public List<AccessAttempt> gateIncidents(String station) {
    return jdbc.query("""
        SELECT a.* FROM access_attempt a WHERE a.command_state='EXPIRED'
        AND (CAST(? AS VARCHAR(100)) IS NULL OR a.station_id=?)
        AND NOT EXISTS (SELECT 1 FROM gate_incident_review r WHERE r.event_id=a.event_id)
        ORDER BY a.received_at,a.event_id LIMIT 100
        """, (rs, row) -> AccessAttemptMapper.fromRow(rs), station, station);
  }

  public boolean reviewGateIncident(String eventId, boolean passed, String note, String actor,
      String correlation, Instant now) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      var observed = findAttempt(eventId).orElse(null);
      if (observed == null || observed.command() != Command.EXPIRED) return false;
      jdbc.query("SELECT credential_id FROM credential WHERE credential_id=? FOR UPDATE",
          rs -> { return rs.next(); }, observed.credentialId());
      var attempt = lockAttempt(eventId);
      if (Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS (SELECT 1 FROM gate_incident_review WHERE event_id=?)
          """, Boolean.class, eventId))) return false;
      String outcome = passed ? "PASSAGE_CONFIRMED" : "NO_PASSAGE";
      jdbc.update("""
          INSERT INTO gate_incident_review(event_id,resolution,note,reviewer,reviewed_at) VALUES(?,?,?,?,?)
          """, eventId, outcome, note, actor, AccessAttemptMapper.timestamp(now));
      writeOutcome(attempt.withGateReview(passed));
      if (passed && attempt.attendanceGate() && !attempt.suspicious() && !hasNewerPresence(attempt)) {
        presence.manuallyConfirmed(attempt.siteId().value(), attempt.employeeId(), attempt.direction(), eventId);
      }
      appendAuditRow(actor, "GATE_INCIDENT_REVIEWED", eventId, outcome, correlation, now);
      return true;
    }));
  }

  private boolean hasNewerPresence(AccessAttempt attempt) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM access_attempt WHERE site_id=? AND employee_id=? AND attendance_gate=TRUE
          AND event_id<>? AND scanned_at>=? AND (command_state IN ('SENT','UNKNOWN','ACKED') OR evidence='MANUAL_PASSAGE'))
        OR EXISTS (SELECT 1 FROM employee_presence WHERE site_id=? AND employee_id=?
          AND evidence_quality='MANUAL_CORRECTION' AND updated_at>?)
        """, Boolean.class, attempt.siteId().value(), attempt.employeeId(), attempt.eventId().value(),
        AccessAttemptMapper.timestamp(attempt.scannedAt()), attempt.siteId().value(), attempt.employeeId(),
        AccessAttemptMapper.timestamp(attempt.scannedAt())));
  }

  public void attendanceDone(AccessAttempt attempt) {
    jdbc.update(
        "UPDATE edge_outbox SET attendance_status='DONE' WHERE event_id=? AND payload=?",
        attempt.eventId().value(), outboxCodec.encode(attempt)
    );
  }

  @Override
  public List<String> pendingCentral(int limit) {
    return jdbc.query("""
        SELECT o.payload FROM edge_outbox o JOIN access_attempt a ON a.event_id=o.event_id
        WHERE o.central_status='PENDING' AND a.command_state <> 'SENT'
        ORDER BY o.created_at LIMIT ?
        """, (rs, row) -> rs.getString(1), limit);
  }

  @Override
  public void centralDone(String payload) {
    jdbc.update(
        "UPDATE edge_outbox SET central_status='DONE' WHERE event_id=? AND payload=?",
        outboxCodec.eventId(payload), payload
    );
  }

  @Override
  public boolean reserve(String eventId, String requestFingerprint) {
    try {
      return jdbc.update(
          "INSERT INTO idempotency_request(event_id, request_fingerprint, state) VALUES(?,?,'IN_PROGRESS')",
          eventId,
          requestFingerprint
      ) == 1;
    } catch (DuplicateKeyException duplicate) {
      return reclaimStaleReservation(eventId, requestFingerprint);
    }
  }

  @Override
  public void complete(String eventId, AccessAttempt result) {
    jdbc.update(
        "UPDATE idempotency_request SET state='TERMINAL', jti=COALESCE(?, jti), updated_at=CURRENT_TIMESTAMP WHERE event_id=?",
        result.jti(),
        eventId
    );
  }

  @Override
  public void cancel(String eventId) {
    jdbc.update("DELETE FROM idempotency_request WHERE event_id=? AND state='IN_PROGRESS'", eventId);
  }

  @Override
  public Optional<IdempotencyEntry> findEvent(String eventId) {
    return jdbc.query(
        "SELECT request_fingerprint, state, updated_at FROM idempotency_request WHERE event_id=?",
        rs -> {
          if (!rs.next()) {
            return Optional.empty();
          }
          boolean progress = "IN_PROGRESS".equals(rs.getString("state"));
          String fingerprint = rs.getString("request_fingerprint");
          AccessAttempt result = findAttempt(eventId).orElse(null);
          if (progress && result == null && isStale(rs.getTimestamp("updated_at").toInstant())) {
            return Optional.empty();
          }
          return Optional.of(new IdempotencyEntry(
              fingerprint,
              progress,
              result
          ));
        },
        eventId
    );
  }

  private boolean reclaimStaleReservation(String eventId, String requestFingerprint) {
    return tx.execute(status -> {
      var state = jdbc.query(
          "SELECT state, updated_at FROM idempotency_request WHERE event_id=? FOR UPDATE",
          rs -> rs.next() ? new ReservationRow(rs.getString("state"), rs.getTimestamp("updated_at").toInstant()) : null,
          eventId
      );
      if (state == null || !"IN_PROGRESS".equals(state.state()) || !isStale(state.updatedAt())) return false;
      if (findAttempt(eventId).isPresent()) return false;
      return jdbc.update(
          "UPDATE idempotency_request SET request_fingerprint=?, updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND state='IN_PROGRESS'",
          requestFingerprint, eventId
      ) == 1;
    });
  }

  private boolean isStale(Instant updatedAt) {
    return updatedAt.plus(reservationTtl).isBefore(Instant.now());
  }

  private record ReservationRow(String state, Instant updatedAt) {}

  private void insertAttempt(AccessAttempt attempt, String jti) {
    jdbc.update(
        INSERT_ATTEMPT,
        attempt.eventId().value(),
        attempt.correlationId(),
        attempt.employeeId(),
        attempt.siteId().value(),
        attempt.stationId().value(),
        attempt.direction().name(),
        AccessAttemptMapper.timestamp(attempt.scannedAt()),
        AccessAttemptMapper.timestamp(attempt.receivedAt()),
        attempt.credentialId(),
        attempt.credentialVersion(),
        jti,
        attempt.decision().name(),
        attempt.reasonCode() == null ? null : attempt.reasonCode().name(),
        attempt.command().name(),
        attempt.passage().name(),
        attempt.offlineAccepted(),
        attempt.evidence() == null ? null : attempt.evidence().name(),
        attempt.suspicious(), attempt.attendanceGate(), attempt.channel().name()
    );
  }

  private void appendAuditRow(
      String actor,
      String action,
      String target,
      String result,
      String correlation,
      Instant time
  ) {
    try {
      jdbc.queryForObject("SELECT id FROM audit_lock WHERE id=1 FOR UPDATE", Integer.class);
      String previous = jdbc.query(
          "SELECT entry_hash FROM edge_audit ORDER BY id DESC LIMIT 1",
          rs -> rs.next() ? rs.getString(1) : "0"
      );
      Instant storedTime = time.truncatedTo(ChronoUnit.MICROS);
      String hash = AuditHash.calculate(previous, actor, action, target, result, correlation, storedTime);
      jdbc.update(
          "INSERT INTO edge_audit(actor, action, target, result, correlation_id, occurred_at, previous_hash, entry_hash, hash_version) "
              + "VALUES(?,?,?,?,?,?,?,?,2)",
          actor,
          action,
          target,
          result,
          correlation,
          AccessAttemptMapper.timestamp(storedTime),
          previous,
          hash
      );
    } catch (Exception failed) {
      throw new IllegalStateException("audit append failed", failed);
    }
  }
}
