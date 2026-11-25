package tmc.access.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.Direction;
import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.CommandStatusReader;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.CredentialWriter;
import tmc.access.domain.port.GateResult;
import tmc.access.domain.port.IdempotencyEntry;
import tmc.access.domain.port.IdempotencyStore;
import tmc.access.domain.port.OutboxPublisher;
import tmc.access.domain.port.PresenceTracker;
import tmc.access.domain.port.UsedTokenStore;
import tmc.shared.audit.AuditWriter;

public final class InMemoryEdgeStore
    implements AttemptStore,
        UsedTokenStore,
        IdempotencyStore,
        CredentialReader,
        CredentialWriter,
        PresenceTracker,
        AuditWriter,
        OutboxPublisher,
        CommandStatusReader {
  private final Map<String, AccessAttempt> attempts = new HashMap<>();
  private final InMemoryCredentialStore credentials = new InMemoryCredentialStore();
  private final Map<String, String> used = new HashMap<>();
  private final Map<String, IdempotencyEntry> idempotency = new HashMap<>();
  private final InMemoryPresenceTracker presence = new InMemoryPresenceTracker();
  private final List<String> audit = new ArrayList<>();
  private final List<AccessAttempt> outbox = new ArrayList<>();

  @Override
  public synchronized Optional<Credential> getCredential(String id) {
    return credentials.getCredential(id);
  }

  @Override
  public synchronized void save(Credential credential) {
    credentials.save(credential);
  }

  @Override
  public synchronized boolean consume(String siteId, String jti, String eventId) {
    String key = siteId + ":" + jti;
    String prior = used.get(key);
    if (prior != null) {
      return prior.equals(eventId);
    }
    used.put(key, eventId);
    return true;
  }

  @Override
  public synchronized boolean alreadyConsumed(String siteId, String jti) {
    return used.containsKey(siteId + ":" + jti);
  }

  @Override
  public synchronized void persistPrepared(AccessAttempt attempt, String jti) {
    if (attempts.containsKey(attempt.eventId().value())) {
      throw new IllegalStateException("duplicate event");
    }
    if (!consume(attempt.siteId().value(), jti, attempt.eventId().value())) {
      throw new tmc.access.domain.ReplayDetectedException();
    }
    attempts.put(attempt.eventId().value(), attempt);
    outbox.add(attempt);
  }

  @Override
  public synchronized void persistDenied(AccessAttempt attempt) {
    attempts.putIfAbsent(attempt.eventId().value(), attempt);
    complete(attempt.eventId().value(), attempt);
  }

  @Override
  public synchronized void update(AccessAttempt attempt) {
    if (!attempts.containsKey(attempt.eventId().value())) {
      throw new IllegalStateException("attempt missing");
    }
    attempts.put(attempt.eventId().value(), attempt);
  }

  @Override
  public synchronized void recordOutcome(AccessAttempt attempt) {
    update(attempt);
    if (attempt.command() == tmc.access.domain.AccessStates.Command.ACKED) {
      acknowledged(attempt.siteId().value(), attempt.employeeId(), attempt.direction(), attempt.eventId().value());
    }
    complete(attempt.eventId().value(), attempt);
  }

  @Override
  public synchronized Optional<AccessAttempt> findAttempt(String eventId) {
    return Optional.ofNullable(attempts.get(eventId));
  }

  @Override
  public synchronized Optional<IdempotencyEntry> findEvent(String eventId) {
    return Optional.ofNullable(idempotency.get(eventId));
  }

  @Override
  public synchronized boolean reserve(String eventId, String requestFingerprint) {
    if (idempotency.containsKey(eventId)) {
      return false;
    }
    idempotency.put(eventId, new IdempotencyEntry(requestFingerprint, true, null));
    return true;
  }

  @Override
  public synchronized void cancel(String eventId) {
    idempotency.remove(eventId);
  }

  @Override
  public synchronized void complete(String eventId, AccessAttempt attempt) {
    IdempotencyEntry entry = idempotency.get(eventId);
    if (entry != null) {
      idempotency.put(eventId, new IdempotencyEntry(entry.requestFingerprint(), false, attempt));
    }
  }

  @Override
  public synchronized boolean allowed(String siteId, String employeeId, Direction direction) {
    return presence.allowed(siteId, employeeId, direction);
  }

  @Override
  public synchronized void acknowledged(String siteId, String employeeId, Direction direction, String eventId) {
    presence.acknowledged(siteId, employeeId, direction, eventId);
  }

  @Override
  public synchronized void confirmed(String siteId, String employeeId, Direction direction, String eventId) {
    presence.confirmed(siteId, employeeId, direction, eventId);
  }

  @Override
  public synchronized void append(
      String actor,
      String action,
      String target,
      String result,
      String correlation,
      Instant time
  ) {
    audit.add(actor + "|" + action + "|" + target + "|" + result + "|" + correlation + "|" + time);
  }

  @Override
  public synchronized void publish(AccessAttempt attempt) {
    outbox.add(attempt);
  }

  @Override
  public synchronized GateResult status(String commandId) {
    return GateResult.TIMEOUT;
  }

  public synchronized List<AccessAttempt> attempts() {
    return List.copyOf(attempts.values());
  }

  public synchronized List<String> audits() {
    return List.copyOf(audit);
  }

  public synchronized List<AccessAttempt> outbox() {
    return List.copyOf(outbox);
  }
}
